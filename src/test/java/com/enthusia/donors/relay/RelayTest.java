package com.enthusia.donors.relay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RelayTest {
    @TempDir Path dir;
    private static final String KEY="a-test-key-with-at-least-32-bytes-long";
    private RelayStore store() {
        return new RelayStore(() -> DriverManager.getConnection("jdbc:sqlite:"+dir.resolve("shared.db")),RelayStore.Dialect.SQLITE);
    }
    private RelayEvent event(String id) {
        return RelayEvent.sign("test-network",id,"backend-a",Instant.now(),300,List.of("{\"text\":\"[TEST] Donation\"}"),KEY);
    }
    @Test void signatureRoundTripRejectsTamperingWrongKeyAndExpiry() {
        var event=event("event-a");
        assertTrue(RelayEvent.parse(event.json()).verify("test-network",KEY,Instant.now()));
        assertFalse(event.verify("another-source",KEY,Instant.now()));
        assertFalse(event.verify("test-network","different-key-with-32-bytes-abcdef",Instant.now()));
        assertFalse(event.verify("test-network",KEY,Instant.now().plusSeconds(301)));
        assertFalse(RelayEvent.parse(event.json().replace("Donation","Tampered")).verify("test-network",KEY,Instant.now()));
    }
    @Test void immutablePublicationAndLocalRestartRetry() throws Exception {
        var remote=store();remote.init();var event=event("event-a");
        Path path=dir.resolve("outbox.db");var local=new LocalOutbox(path);local.init();local.put(event);
        var restored=new LocalOutbox(path);restored.init();
        assertEquals(List.of(event),restored.pending());
        assertThrows(SQLException.class,() -> restored.flush(new RelayStore(() -> {throw new SQLException("outage");},RelayStore.Dialect.SQLITE)));
        assertEquals(List.of(event),restored.pending());
        restored.flush(remote);assertTrue(restored.pending().isEmpty());
        remote.publish(event);assertEquals(1,remote.pending("test-network","proxy-a",10).size());
        assertThrows(SQLException.class,() -> remote.publish(RelayEvent.sign("test-network","event-a","backend-a",Instant.now(),300,List.of("{\"text\":\"conflict\"}"),KEY)));
    }
    @Test void proxyRestartAndCompetingClaimsCannotRepeatDelivery() throws Exception {
        var remote=store();remote.init();var event=event("event-a");remote.publish(event);
        var sends=new AtomicInteger();var player=UUID.randomUUID();
        var dispatcher=new RelayDispatcher(remote,"test-network","proxy-a",KEY);
        assertEquals(1,dispatcher.poll(List.of(player),(e,players) -> () -> sends.incrementAndGet()));
        assertEquals("DELIVERED",remote.receipt("test-network","event-a","proxy-a"));
        var restarted=new RelayDispatcher(store(),"test-network","proxy-a",KEY);
        restarted.poll(List.of(player),(e,players) -> () -> sends.incrementAndGet());
        assertEquals(1,sends.get());
        assertTrue(remote.claim(event,"proxy-b"));
        assertFalse(store().claim(event,"proxy-b"));
    }
    @Test void noPlayersLeavesPendingAndPartialSendIsUncertainWithoutReplay() throws Exception {
        var remote=store();remote.init();remote.publish(event("event-a"));
        var dispatcher=new RelayDispatcher(remote,"test-network","proxy-a",KEY);var sends=new AtomicInteger();
        dispatcher.poll(List.of(),(e,players) -> () -> sends.incrementAndGet());
        assertEquals("PENDING",remote.receipt("test-network","event-a","proxy-a"));
        dispatcher.poll(List.of(UUID.randomUUID()),(e,players) -> () -> {sends.incrementAndGet();throw new IllegalStateException("partial send");});
        assertEquals("UNCERTAIN",remote.receipt("test-network","event-a","proxy-a"));
        dispatcher.poll(List.of(UUID.randomUUID()),(e,players) -> () -> sends.incrementAndGet());
        assertEquals(1,sends.get());
    }
    @Test void claimedCrashAndFailedCompletionRemainSuppressed() throws Exception {
        var remote=store();remote.init();var event=event("event-a");remote.publish(event);assertTrue(remote.claim(event,"proxy-a"));
        assertTrue(store().pending("test-network","proxy-a",10).isEmpty());
        assertEquals("CLAIMED",store().receipt("test-network","event-a","proxy-a"));
        assertEquals(1,store().pending("test-network","other-proxy",10).size());
    }
    @Test void badSignatureIsRejectedBeforeSenderPreparation() throws Exception {
        var remote=store();remote.init();remote.publish(RelayEvent.parse(event("event-a").json().replace("Donation","tampered")));
        var calls=new AtomicInteger();
        new RelayDispatcher(remote,"test-network","proxy-a",KEY).poll(List.of(UUID.randomUUID()),(e,players) -> {calls.incrementAndGet();return () -> {};});
        assertEquals(0,calls.get());
        assertEquals("REJECTED",remote.receipt("test-network","event-a","proxy-a"));
    }
    @Test void concurrentProxyWorkersOnlyOneClaimsEvent() throws Exception {
        var remote=store();remote.init();var event=event("event-a");remote.publish(event);
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        var start=new java.util.concurrent.CountDownLatch(1);
        try {
            var first=executor.submit(() -> {start.await();return store().claim(event,"proxy-a");});
            var second=executor.submit(() -> {start.await();return store().claim(event,"proxy-a");});
            start.countDown();assertNotEquals(first.get(),second.get());
        } finally {executor.shutdownNow();}
    }
    @Test void completionWriteFailureCannotReplaySentChat() throws Exception {
        var remote=store();remote.init();remote.publish(event("event-a"));
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+dir.resolve("shared.db"));var s=c.createStatement()) {
            s.execute("CREATE TRIGGER reject_finish BEFORE UPDATE ON enthusiadonors_test_receipts BEGIN SELECT RAISE(ABORT,'injected'); END");
        }
        var sends=new AtomicInteger();var dispatcher=new RelayDispatcher(remote,"test-network","proxy-a",KEY);
        assertThrows(Exception.class,() -> dispatcher.poll(List.of(UUID.randomUUID()),(e,p) -> () -> sends.incrementAndGet()));
        assertEquals("CLAIMED",remote.receipt("test-network","event-a","proxy-a"));
        dispatcher.poll(List.of(UUID.randomUUID()),(e,p) -> () -> sends.incrementAndGet());
        assertEquals(1,sends.get());
    }
    @Test void malformedPayloadCannotBlockFollowingGoodEvent() throws Exception {
        var remote=store();remote.init();remote.publish(event("event-a"));remote.publish(event("event-b"));
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+dir.resolve("shared.db"));var s=c.createStatement()) {s.executeUpdate("UPDATE enthusiadonors_test_events SET payload='{}' WHERE event_id='event-a'");}
        var calls=new AtomicInteger();new RelayDispatcher(remote,"test-network","proxy-a",KEY).poll(List.of(UUID.randomUUID()),(e,p) -> () -> calls.incrementAndGet());
        assertEquals(1,calls.get());assertEquals("REJECTED",remote.receipt("test-network","event-a","proxy-a"));
    }
    @Test void expiryPreventsClaimsAndLocalReplay() throws Exception {
        var expired=RelayEvent.sign("test-network","event-a","backend-a",Instant.now().minusSeconds(400),300,List.of("{\"text\":\"old\"}"),KEY);
        var remote=store();remote.init();remote.publish(expired);
        assertFalse(remote.claim(expired,"proxy-a"));assertTrue(remote.pending("test-network","proxy-a",10).isEmpty());
        var outbox=new LocalOutbox(dir.resolve("outbox.db"));outbox.init();outbox.put(expired);outbox.flush(remote);assertTrue(outbox.pending().isEmpty());
    }
    @Test void richComponentsSurviveSignedRelayEnvelope() {
        var component=net.kyori.adventure.text.Component.text("[TEST] Store",net.kyori.adventure.text.format.TextColor.color(0x17ccff))
                .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD)
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl("https://example.com/store"));
        var serializer=net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson();
        var event=RelayEvent.sign("test-network","event-a","backend-a",Instant.now(),300,List.of(serializer.serialize(component)),KEY);
        var decoded=RelayEvent.parse(event.json());assertTrue(decoded.verify("test-network",KEY,Instant.now()));
        assertEquals(component,serializer.deserialize(decoded.lines().getFirst()));
    }
    @Test void conflictingOutboxEventDoesNotBlockOtherEvents() throws Exception {
        var remote=store();remote.init();remote.publish(event("event-a"));
        var outbox=new LocalOutbox(dir.resolve("outbox.db"));outbox.init();
        outbox.put(RelayEvent.sign("test-network","event-a","backend-b",Instant.now(),300,List.of("{\"text\":\"conflict\"}"),KEY));
        outbox.put(event("event-b"));outbox.flush(remote);
        assertTrue(outbox.pending().isEmpty());assertEquals(2,remote.pending("test-network","proxy-a",10).size());
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+dir.resolve("outbox.db"));var s=c.createStatement();var r=s.executeQuery("SELECT state FROM relay_outbox WHERE event_id='event-a'")) {
            assertTrue(r.next());assertEquals("CONFLICT",r.getString(1));
        }
    }
}
