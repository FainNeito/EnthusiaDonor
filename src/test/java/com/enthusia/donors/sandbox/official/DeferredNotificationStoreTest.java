package com.enthusia.donors.sandbox.official;

import com.enthusia.donors.model.PaymentRecord;
import com.enthusia.donors.sandbox.official.notifications.NotificationStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DeferredNotificationStoreTest {
    private static final Instant BASE=Instant.parse("2026-11-10T12:00:00Z");
    private PaymentRecord payment() {return new PaymentRecord("same",UUID.randomUUID(),"Donor",BigDecimal.TEN,"USD","complete",BASE.plusSeconds(1),false,false,List.of(1),BASE);}
    private NotificationStore.Payload generic(PaymentRecord p) {return new NotificationStore.Payload("generic-chat","generic-discord");}
    private NotificationStore.Payload renewal(PaymentRecord p) {return new NotificationStore.Payload("renewal-chat","renewal-discord");}
    private void verify(NotificationStore store) throws Exception {
        store.init(); store.observe(List.of(),BASE,p->true,this::generic);
        var p=payment();
        assertEquals(2,store.observeDeferred(List.of(p),BASE.plusSeconds(2),x->true,this::generic));
        assertTrue(store.pending().isEmpty());
        assertEquals(0,store.observeWebhookDeferred(p,BASE.plusSeconds(3),x->true,this::renewal,true));
        assertEquals(0,store.releaseDeferred(BASE.plusSeconds(31),30));
        assertEquals(2,store.releaseDeferred(BASE.plusSeconds(32),30));
        var pending=store.pending(); assertEquals(2,pending.size());
        assertTrue(pending.stream().allMatch(j->j.payload().startsWith("renewal-")));
        store.observeWebhookDeferred(p,BASE.plusSeconds(33),x->true,x->new NotificationStore.Payload("late-chat","late-discord"),true);
        assertTrue(store.pending().stream().allMatch(j->j.payload().startsWith("renewal-")));
        assertEquals(0,store.releaseDeferred(BASE.plusSeconds(40),30));
    }
    @Test void renewalFirstCannotBeOverwrittenByLaterGenericPoll(@TempDir Path dir) throws Exception {
        var store=new NotificationStore(()->DriverManager.getConnection("jdbc:sqlite:"+dir.resolve("reverse.db")),NotificationStore.Dialect.SQLITE,"fixture");
        store.init(); store.observe(List.of(),BASE,p->true,this::generic); var p=payment();
        assertEquals(2,store.observeWebhookDeferred(p,BASE.plusSeconds(2),x->true,this::renewal,true));
        assertEquals(0,store.observeDeferred(List.of(p),BASE.plusSeconds(3),x->true,this::generic));
        assertEquals(2,store.releaseDeferred(BASE.plusSeconds(32),30));
        assertTrue(store.pending().stream().allMatch(j->j.payload().startsWith("renewal-")));
    }
    @Test void sqliteDurableOrdering(@TempDir Path dir) throws Exception {
        verify(new NotificationStore(()->DriverManager.getConnection("jdbc:sqlite:"+dir.resolve("ordering.db")),NotificationStore.Dialect.SQLITE,"fixture"));
    }
    @Test void heldJobsSurviveStoreReopenAndReleaseAtDeadline(@TempDir Path dir) throws Exception {
        var db=dir.resolve("reopen.db");
        NotificationStore.Connections connections=()->DriverManager.getConnection("jdbc:sqlite:"+db);
        var first=new NotificationStore(connections,NotificationStore.Dialect.SQLITE,"fixture");
        first.init(); first.observe(List.of(),BASE,p->true,this::generic);
        first.observeDeferred(List.of(payment()),BASE.plusSeconds(2),x->true,this::generic);
        var reopened=new NotificationStore(connections,NotificationStore.Dialect.SQLITE,"fixture");
        assertTrue(reopened.pending().isEmpty());
        assertEquals(2,reopened.releaseDeferred(BASE.plusSeconds(32),30));
        assertEquals(2,reopened.pending().size());
    }
    @Test void concurrentEnrichmentAndReleaseRemainSingleImmutableJobPerChannel(@TempDir Path dir) throws Exception {
        var store=new NotificationStore(()->DriverManager.getConnection("jdbc:sqlite:"+dir.resolve("race.db")),NotificationStore.Dialect.SQLITE,"fixture");
        store.init(); store.observe(List.of(),BASE,p->true,this::generic); var p=payment();
        store.observeDeferred(List.of(p),BASE.plusSeconds(2),x->true,this::generic);
        var gate=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{gate.await(); return store.releaseDeferred(BASE.plusSeconds(32),30);});
            var b=pool.submit(()->{gate.await(); return store.observeWebhookDeferred(p,BASE.plusSeconds(32),x->true,this::renewal,true);});
            gate.countDown(); a.get(); b.get();
        }
        var jobs=store.pending();assertEquals(2,jobs.size());
        var names=jobs.stream().map(j->j.payload().substring(0,j.payload().indexOf('-'))).collect(java.util.stream.Collectors.toSet());
        assertEquals(1,names.size());
        assertEquals(0,store.releaseDeferred(BASE.plusSeconds(60),30));
    }
    @Test void mariadbDurableOrdering() throws Exception {
        String url=System.getenv("DONOR_FIXTURE_JDBC"); org.junit.jupiter.api.Assumptions.assumeTrue(url!=null && !url.isBlank());
        NotificationStore.Connections connections=()->DriverManager.getConnection(url,"root","");
        verify(new NotificationStore(connections,NotificationStore.Dialect.MARIADB,"fixture"));
        var reverse=new NotificationStore(connections,NotificationStore.Dialect.MARIADB,"reverse");
        reverse.init();reverse.observe(List.of(),BASE,p->true,this::generic);var r=payment();
        assertEquals(2,reverse.observeWebhookDeferred(r,BASE.plusSeconds(2),x->true,this::renewal,true));
        assertEquals(0,reverse.observeDeferred(List.of(r),BASE.plusSeconds(3),x->true,this::generic));
        assertEquals(2,reverse.releaseDeferred(BASE.plusSeconds(32),30));
        assertTrue(reverse.pending().stream().allMatch(j->j.payload().startsWith("renewal-")));
        var first=new NotificationStore(connections,NotificationStore.Dialect.MARIADB,"reopen");
        first.init();first.observe(List.of(),BASE,p->true,this::generic);
        first.observeDeferred(List.of(payment()),BASE.plusSeconds(2),x->true,this::generic);
        var reopened=new NotificationStore(connections,NotificationStore.Dialect.MARIADB,"reopen");
        assertTrue(reopened.pending().isEmpty());assertEquals(2,reopened.releaseDeferred(BASE.plusSeconds(32),30));
        var race=new NotificationStore(connections,NotificationStore.Dialect.MARIADB,"race");
        race.init();race.observe(List.of(),BASE,p->true,this::generic);var racy=payment();
        race.observeDeferred(List.of(racy),BASE.plusSeconds(2),x->true,this::generic);
        var gate=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{gate.await();return race.releaseDeferred(BASE.plusSeconds(32),30);});
            var b=pool.submit(()->{gate.await();return race.observeWebhookDeferred(racy,BASE.plusSeconds(32),x->true,this::renewal,true);});
            gate.countDown();a.get();b.get();
        }
        var jobs=race.pending();assertEquals(2,jobs.size());
        assertEquals(1,jobs.stream().map(j->j.payload().substring(0,j.payload().indexOf('-'))).distinct().count());
    }
}
