package com.enthusia.donors.network;

import com.enthusia.donors.cache.DonorCache;
import com.enthusia.donors.model.DonorEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SharedProjectionTest {
    @TempDir Path temporary;
    @Test void preprovisionedPublisherRegistersWithoutSchemaPrivileges() throws Exception {
        store("provisioner").initPublisher();
        String url="jdbc:sqlite:"+temporary.resolve("shared.db");
        var publisher=new JdbcProjectionStore(() -> {
            var actual=DriverManager.getConnection(url);
            return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{java.sql.Connection.class},(proxy,method,args) -> {
                        if(method.getName().equals("createStatement")) {
                            var statement=actual.createStatement();
                            return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                                    new Class<?>[]{java.sql.Statement.class},(statementProxy,statementMethod,statementArgs) -> {
                                        if(statementArgs!=null && statementArgs.length>0 && statementArgs[0] instanceof String sql
                                                && sql.trim().toUpperCase(Locale.ROOT).startsWith("CREATE"))
                                            throw new java.sql.SQLException("Schema privileges denied");
                                        try {return statementMethod.invoke(statement,statementArgs);}
                                        catch(java.lang.reflect.InvocationTargetException failure) {throw failure.getCause();}
                                    });
                        }
                        try {return method.invoke(actual,args);}
                        catch(java.lang.reflect.InvocationTargetException failure) {throw failure.getCause();}
                    });
        },"test-network",JdbcProjectionStore.Dialect.SQLITE);
        publisher.initPublisher(false);
        assertTrue(publisher.acquireLease("writer",60_000));
        assertEquals(1,publisher.publish("writer",draft("Ready")).revision());
    }
    @Test void preprovisionedPublisherFailsWhenSchemaIsMissing() {
        assertThrows(java.sql.SQLException.class,() -> store("test-network").initPublisher(false));
    }
    private JdbcProjectionStore store(String source) {
        String url="jdbc:sqlite:"+temporary.resolve("shared.db");
        return new JdbcProjectionStore(() -> DriverManager.getConnection(url),source,JdbcProjectionStore.Dialect.SQLITE);
    }
    private NetworkSnapshot draft(String name) {
        long now=Instant.parse("2026-10-01T12:00:00Z").toEpochMilli();
        return new NetworkSnapshot(1,"test-network",0,now,"2026-10","America/Chicago",
                new NetworkSnapshot.Display("$",true,10,"None","$0.00","-"),
                List.of(new DonorEntry(UUID.fromString("365bfa21-8032-49ee-9b63-4fe890c9d43f"),name,
                        new BigDecimal("100.00"),new BigDecimal("25.00"),1,1,now)));
    }
    @Test void twoIndependentReadersSeeSameRevisionAndEmptyIsFound() throws Exception {
        var publisher=store("test-network");publisher.initPublisher();
        assertEquals(SnapshotRead.Status.NOT_FOUND,store("test-network").read().status());
        assertTrue(publisher.acquireLease("publisher-a",60_000));
        var committed=publisher.publish("publisher-a",draft("OfficialDonor"));
        assertEquals(1,committed.revision());
        assertEquals(committed,store("test-network").read().snapshot());
        assertEquals(committed,store("test-network").read().snapshot());
        var empty=new NetworkSnapshot(1,"test-network",0,committed.generatedAt(),"2026-10","America/Chicago",committed.display(),List.of());
        publisher.publish("publisher-a",empty);
        assertEquals(SnapshotRead.Status.FOUND,store("test-network").read().status());
        assertTrue(store("test-network").read().snapshot().donors().isEmpty());
    }
    @Test void competingPublisherCannotWriteAndExpiredOwnerIsFenced() throws Exception {
        var first=store("test-network");first.initPublisher();
        var second=store("test-network");
        assertTrue(first.acquireLease("first",60_000));
        assertFalse(second.acquireLease("second",60_000));
        assertThrows(Exception.class,() -> second.publish("second",draft("Wrong")));
        first.publish("first",draft("First"));
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+temporary.resolve("shared.db"));var statement=c.createStatement()) {
            statement.executeUpdate("UPDATE enthusiadonors_test_projection SET lease_until=0");
        }
        assertTrue(second.acquireLease("second",60_000));
        assertThrows(Exception.class,() -> first.publish("first",draft("Stale")));
        assertEquals(2,second.publish("second",draft("Second")).revision());
    }
    @Test void failedPublicationRollsBackRevisionAndPayload() throws Exception {
        var publisher=store("test-network");publisher.initPublisher();publisher.acquireLease("writer",60_000);
        var before=publisher.publish("writer",draft("Before"));
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+temporary.resolve("shared.db"));var statement=c.createStatement()) {
            statement.execute("CREATE TRIGGER reject_publish BEFORE UPDATE OF payload ON enthusiadonors_test_projection BEGIN SELECT RAISE(ABORT,'injected failure'); END");
        }
        assertThrows(Exception.class,() -> publisher.publish("writer",draft("After")));
        assertEquals(before,publisher.read().snapshot());
    }
    @Test void expiredAndReacquiredSameTokenRejectsEarlierFetch() throws Exception {
        var publisher=store("test-network");publisher.initPublisher();publisher.acquireLease("writer",60_000);
        long earlierEpoch=publisher.leaseEpoch("writer");
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+temporary.resolve("shared.db"));var statement=c.createStatement()) {
            statement.executeUpdate("UPDATE enthusiadonors_test_projection SET lease_until=0");
        }
        assertTrue(publisher.acquireLease("writer",60_000));
        assertTrue(publisher.leaseEpoch("writer")>earlierEpoch);
        assertThrows(Exception.class,() -> publisher.publish("writer",earlierEpoch,draft("Delayed")));
        assertEquals(SnapshotRead.Status.NOT_FOUND,publisher.read().status());
        assertEquals(1,publisher.publish("writer",draft("Current")).revision());
    }
    @Test void futureClockAndChangedSameRevisionDoNotReplaceCache() {
        var display=new DonorCache();var consumer=new SharedSnapshotConsumer("test-network",display);
        var good=draft("OfficialDonor").withRevision(5);
        Instant now=Instant.ofEpochMilli(good.generatedAt());
        assertTrue(consumer.accept(SnapshotRead.found(good),now,1200));
        assertFalse(consumer.accept(SnapshotRead.found(draft("Changed").withRevision(5)),now,1200));
        assertFalse(consumer.accept(SnapshotRead.found(good.withRevision(6)),now.minusSeconds(301),1200));
        assertEquals("OfficialDonor",display.snapshot().topAlltime(1).orElseThrow().name());
    }
    @Test void cachePreservesGoodSnapshotOnFailureAbsenceAndOlderRevision() {
        var display=new DonorCache();var consumer=new SharedSnapshotConsumer("test-network",display);
        var good=draft("OfficialDonor").withRevision(5);
        Instant now=Instant.ofEpochMilli(good.generatedAt());
        assertTrue(consumer.accept(SnapshotRead.found(good),now,1200));
        assertFalse(consumer.accept(SnapshotRead.failed(),now,1200));
        assertFalse(consumer.accept(SnapshotRead.notFound(),now,1200));
        assertFalse(consumer.accept(SnapshotRead.found(draft("Old").withRevision(4)),now,1200));
        assertEquals("OfficialDonor",display.snapshot().topAlltime(1).orElseThrow().name());
        assertEquals(5,consumer.revision());
    }
    @Test void rolloverHidesPreviousMonthlyDataButRetainsAlltime() {
        var display=new DonorCache();var consumer=new SharedSnapshotConsumer("test-network",display);
        consumer.accept(SnapshotRead.found(draft("OfficialDonor").withRevision(1)),Instant.parse("2026-11-01T12:00:00Z"),1200);
        assertTrue(display.snapshot().monthly().isEmpty());
        assertEquals(1,display.snapshot().alltime().size());
    }
    @Test void anotherSourceOrMalformedSnapshotCannotReplaceGoodState() throws Exception {
        var publisher=store("test-network");publisher.initPublisher();publisher.acquireLease("writer",60_000);
        publisher.publish("writer",draft("OfficialDonor"));
        assertEquals(SnapshotRead.Status.NOT_FOUND,store("another-source").read().status());
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+temporary.resolve("shared.db"));var statement=c.createStatement()) {
            statement.executeUpdate("UPDATE enthusiadonors_test_projection SET payload='{}'");
        }
        assertEquals(SnapshotRead.Status.FAILED,publisher.read().status());
    }
}
