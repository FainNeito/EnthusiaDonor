package com.enthusia.donors.sandbox.official;

import com.enthusia.donors.sandbox.official.notifications.*;
import com.enthusia.donors.model.PaymentRecord;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PaymentAnnouncementsTest {
    @TempDir Path directory;
    NotificationStore store;
    Instant now=Instant.parse("2026-10-03T12:00:00Z");
    @BeforeEach void setup() throws Exception {store=new NotificationStore(()->DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("notify.db")),NotificationStore.Dialect.SQLITE,"store");store.init();}
    PaymentRecord payment(String id,Instant date,String status) {return new PaymentRecord(id,UUID.nameUUIDFromBytes(id.getBytes()),"Donor",new BigDecimal("30.00"),"USD",status,date,status.equals("refund") || status.equals("chargeback"),false,List.of(1),now);}
    NotificationStore.Payload payload(PaymentRecord p) {return new NotificationStore.Payload("chat:"+p.paymentIdHash(),"discord:"+p.paymentIdHash());}
    void arm() throws Exception {store.observe(List.of(payment("old",now.minusSeconds(60),"complete")),now,p->true,this::payload);}
    @Test void firstSyncBaselinesWithoutHistoricalMessages() throws Exception {arm();assertTrue(store.pending().isEmpty());}
    @Test void emptyFirstSyncAllowsFirstSubsequentDonation() throws Exception {
        store.observe(List.of(),now,p->true,this::payload);var p=payment("first",now.plusSeconds(1),"complete");
        store.observe(List.of(p),now.plusSeconds(2),x->true,this::payload);assertEquals(2,store.pending().size());
    }
    @Test void newPaymentQueuesBothChannelsOnceAcrossRestart() throws Exception {
        arm();var p=payment("new",now.plusSeconds(1),"complete");store.observe(List.of(p),now.plusSeconds(2),x->true,this::payload);
        setup();store.observe(List.of(p),now.plusSeconds(3),x->true,this::payload);assertEquals(2,store.pending().size());
    }
    @Test void oldAndFuturePaymentsNeverAnnounce() throws Exception {arm();store.observe(List.of(payment("late",now.minusSeconds(1),"complete"),payment("future",now.plusSeconds(100),"complete")),now.plusSeconds(2),x->true,this::payload);assertTrue(store.pending().isEmpty());}
    @Test void pendingCanCompleteAndReversalCannotRepeat() throws Exception {
        arm();var waiting=payment("new",now.plusSeconds(1),"pending");store.observe(List.of(waiting),now.plusSeconds(2),p->p.status().equals("complete"),this::payload);
        var paid=payment("new",now.plusSeconds(1),"complete");store.observe(List.of(paid),now.plusSeconds(3),p->true,this::payload);
        store.observe(List.of(payment("new",now.plusSeconds(1),"refund")),now.plusSeconds(4),p->false,this::payload);
        store.observe(List.of(paid),now.plusSeconds(5),p->true,this::payload);assertEquals(2,store.pending().size());
    }
    @Test void legacyParserInvalidFlagOnPendingDoesNotBlockCompletion() throws Exception {
        arm();var p=payment("new",now.plusSeconds(1),"pending");
        var pending=new PaymentRecord(p.paymentIdHash(),p.playerUuid(),p.playerName(),p.amount(),p.currency(),p.status(),p.createdAt(),true,false,p.packageIds(),p.importedAt());
        store.observe(List.of(pending),now.plusSeconds(2),x->false,this::payload);
        store.observe(List.of(payment("new",now.plusSeconds(1),"complete")),now.plusSeconds(3),x->true,this::payload);assertEquals(2,store.pending().size());
    }
    @Test void payloadFailureRollsBackObservationAndAllJobs() throws Exception {
        arm();var p=payment("new",now.plusSeconds(1),"complete");assertThrows(Exception.class,()->store.observe(List.of(p),now.plusSeconds(2),x->true,x->{throw new IllegalStateException();}));
        assertTrue(store.pending().isEmpty());store.observe(List.of(p),now.plusSeconds(3),x->true,this::payload);assertEquals(2,store.pending().size());
    }
    @Test void claimSurvivesCrashAndDoesNotAffectOtherChannel() throws Exception {
        arm();store.observe(List.of(payment("new",now.plusSeconds(1),"complete")),now.plusSeconds(2),x->true,this::payload);
        var discord=store.pending().stream().filter(j->j.channel().equals("discord")).findFirst().orElseThrow();assertTrue(store.claim(discord));setup();assertFalse(store.claim(discord));assertEquals(1,store.pending().size());assertEquals("chat",store.pending().getFirst().channel());
    }
    @Test void publishedChatCanBeRetriedWithSameImmutablePayload() throws Exception {
        arm();store.observe(List.of(payment("new",now.plusSeconds(1),"complete")),now.plusSeconds(2),x->true,this::payload);
        var chat=store.pending().stream().filter(j->j.channel().equals("chat")).findFirst().orElseThrow();setup();assertTrue(store.pending().contains(chat));store.finish(chat,"PUBLISHED");assertEquals(1,store.pending().size());
    }
    @Test void excludedPaymentCannotLaterBecomeAPurchaseByConfigChange() throws Exception {
        arm();var p=payment("new",now.plusSeconds(1),"complete");store.observe(List.of(p),now.plusSeconds(2),x->false,this::payload);store.observe(List.of(p),now.plusSeconds(3),x->true,this::payload);assertTrue(store.pending().isEmpty());
    }
    @Test void discordTimeoutAndServerFailureAreUncertain() {assertEquals("UNCERTAIN",DiscordWebhook.outcome(500));assertEquals("UNCERTAIN",DiscordWebhook.outcome(0));assertEquals("SENT",DiscordWebhook.outcome(200));assertEquals("FAILED",DiscordWebhook.outcome(404));}

    com.enthusia.donors.config.DonorsConfig config() {
        return new com.enthusia.donors.config.DonorsConfig("key",10,10,3,1000,"tebex_payments_only",
                true,true,true,true,Set.of(1),Set.of(2),Set.of(),Set.of("excluded"),ZoneId.of("America/Chicago"),
                "$",true,10,"None","$0.00","-",true,false,Path.of("unused.json"),false,"","","","","","","",false,true,false,12);
    }
    @Test void actualEligibilityRejectsFreeManualReversedUnpaidAndExclusions() {
        var good=payment("good",now,"complete");assertTrue(PaymentAnnouncements.eligible(good,config(),Set.of(),now));
        for(var bad:List.of(payment("excluded",now,"complete"),payment("good",now,"pending"),payment("good",now,"refund"),payment("good",now.plusSeconds(1),"complete"),
                new PaymentRecord("free",good.playerUuid(),"Donor",BigDecimal.ZERO,"USD","complete",now,false,false,List.of(1),now),
                new PaymentRecord("manual",good.playerUuid(),"Donor",BigDecimal.TEN,"USD","complete",now,false,true,List.of(1),now),
                new PaymentRecord("wrong-package",good.playerUuid(),"Donor",BigDecimal.TEN,"USD","complete",now,false,false,List.of(2),now),
                new PaymentRecord("invalid-name",good.playerUuid(),"<click:run_command:/op>",BigDecimal.TEN,"USD","complete",now,false,false,List.of(1),now)))
            assertFalse(PaymentAnnouncements.eligible(bad,config(),Set.of(),now),bad.paymentIdHash());
        assertFalse(PaymentAnnouncements.eligible(good,config(),Set.of("good"),now));
    }
    @Test void preRenderExcludesBaselineAndAlreadyConsumedPayments() throws Exception {
        var p=payment("new",now.plusSeconds(1),"complete");assertTrue(store.candidates(List.of(p),now.plusSeconds(2),x->true).isEmpty());
        arm();assertEquals(List.of(p),store.candidates(List.of(p),now.plusSeconds(2),x->true));
        store.observe(List.of(p),now.plusSeconds(2),x->true,this::payload);assertTrue(store.candidates(List.of(p),now.plusSeconds(3),x->true).isEmpty());
    }
    @Test void duplicateWithinSingleResponseCannotDuplicateJobs() throws Exception {
        arm();var p=payment("new",now.plusSeconds(1),"complete");store.observe(List.of(p,p),now.plusSeconds(2),x->true,this::payload);assertEquals(2,store.pending().size());
    }
    @Test void concurrentPublishersObserveOnce() throws Exception {
        arm();var p=payment("new",now.plusSeconds(1),"complete");
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var one=pool.submit(()->store.observe(List.of(p),now.plusSeconds(2),x->true,this::payload));
            var two=pool.submit(()->store.observe(List.of(p),now.plusSeconds(2),x->true,this::payload));
            assertEquals(2,one.get()+two.get());assertEquals(2,store.pending().size());
        }
    }
    @Test void discordExplicitRateLimitRetriesButTimeoutDoesNot() {
        var calls=new java.util.concurrent.atomic.AtomicInteger();var waits=new ArrayList<Long>();
        var webhook=new DiscordWebhook((uri,payload)->calls.incrementAndGet()==1?new DiscordWebhook.Response(429,"{\"retry_after\":0.5}"):new DiscordWebhook.Response(200,"{}"),waits::add);
        assertEquals("SENT",webhook.send("https://discord.com/api/webhooks/123/token","{}"));assertEquals(2,calls.get());assertEquals(List.of(1000L),waits);
        calls.set(0);webhook=new DiscordWebhook((uri,payload)->{calls.incrementAndGet();throw new java.net.SocketTimeoutException();},waits::add);
        assertEquals("UNCERTAIN",webhook.send("https://discord.com/api/webhooks/123/token","{}"));assertEquals(1,calls.get());
    }
    @Test void excessiveRateLimitDoesNotLoop() {
        var calls=new java.util.concurrent.atomic.AtomicInteger();var webhook=new DiscordWebhook((uri,payload)->{calls.incrementAndGet();return new DiscordWebhook.Response(429,"{\"retry_after\":1000}");},millis->fail());
        assertEquals("FAILED",webhook.send("https://discord.com/api/webhooks/123/token","{}"));assertEquals(1,calls.get());
    }
    @Test void serverFailureIsNeverRetried() {
        var calls=new java.util.concurrent.atomic.AtomicInteger();var webhook=new DiscordWebhook((uri,payload)->{calls.incrementAndGet();return new DiscordWebhook.Response(503,"{}");},millis->fail());
        assertEquals("UNCERTAIN",webhook.send("https://discord.com/api/webhooks/123/token","{}"));assertEquals(1,calls.get());
    }
    @Test void realMessagesArePublicSafeAndHaveNoTestMarker() {
        var settings=new NotificationSettings(true,true,true,"","",false,false);var p=payment("p",now,"complete");
        String json=PaymentMessages.discord(p,settings);assertFalse(json.contains("TEST"));assertFalse(json.contains("30.00"));assertTrue(json.contains("\"parse\":[]"));
        var lines=PaymentMessages.chat(p,new int[64],settings);assertEquals(11,lines.size());
        assertFalse(lines.toString().contains("TEST"));assertTrue(lines.toString().contains("/donors profile"));
    }
    void realJobs() throws Exception {
        arm();var p=payment("new",now.plusSeconds(1),"complete");
        var event=com.enthusia.donors.relay.RelayEvent.sign("store","payment-new","backend",now.plusSeconds(2),900,List.of("{}"),"a".repeat(32));
        store.observe(List.of(p),now.plusSeconds(2),x->true,x->new NotificationStore.Payload(event.json(),"{}"));
    }
    @Test void relayOutageDoesNotBlockDiscordAndRetainsSameSignedPayload() throws Exception {
        realJobs();String saved=store.pending().getFirst().payload();var sends=new java.util.concurrent.atomic.AtomicInteger();
        var dispatcher=new NotificationDispatcher(store,event->{throw new SQLException("offline");},payload->{sends.incrementAndGet();return "SENT";});
        assertEquals(1,dispatcher.pump(now.plusSeconds(3),true,true));assertEquals(1,sends.get());assertEquals(1,store.pending().size());assertEquals(saved,store.pending().getFirst().payload());
        assertTrue(store.status().contains("SENT=1"));
    }
    @Test void uncertainDiscordIsNeverAutomaticallyRetried() throws Exception {
        realJobs();var sends=new java.util.concurrent.atomic.AtomicInteger();var dispatcher=new NotificationDispatcher(store,event->{},payload->{sends.incrementAndGet();return "UNCERTAIN";});
        dispatcher.pump(now.plusSeconds(3),true,true);dispatcher.pump(now.plusSeconds(4),true,true);assertEquals(1,sends.get());assertTrue(store.status().contains("UNCERTAIN=1"));
    }
    @Test void expiredChatNeverPublishesAndDisabledDiscordRemainsPending() throws Exception {
        realJobs();var dispatcher=new NotificationDispatcher(store,event->fail("expired chat sent"),payload->{fail("disabled Discord sent");return "SENT";});
        assertEquals(0,dispatcher.pump(now.plusSeconds(1000),true,false));assertEquals(1,store.pending().size());assertTrue(store.status().contains("EXPIRED=1"));
    }
    @Test void configurationRequiresExplicitMatchingPublisherAndWebhook() {
        var c=new org.bukkit.configuration.file.YamlConfiguration();var standalone=com.enthusia.donors.network.NetworkSettings.load(c);
        assertFalse(NotificationSettings.load(c,standalone).enabled());c.set("notifications.enabled",true);assertThrows(IllegalArgumentException.class,()->NotificationSettings.load(c,standalone));
        c.set("network.mode","publisher");c.set("network.jdbc-url","jdbc:mariadb://host/db");var publisher=com.enthusia.donors.network.NetworkSettings.load(c);
        assertThrows(IllegalArgumentException.class,()->NotificationSettings.load(c,publisher));
        c.set("notifications.discord.webhook-url","https://discord.com/api/webhooks/123/token");c.set("relay.enabled",true);c.set("relay.jdbc-url",publisher.jdbcUrl());c.set("relay.source-id",publisher.sourceId());c.set("relay.backend-id","smp");c.set("relay.signing-key","a".repeat(32));
        assertTrue(NotificationSettings.load(c,publisher).enabled());assertFalse(NotificationSettings.load(c,publisher).toString().contains("token"));
        c.set("relay.source-id","different");assertThrows(IllegalArgumentException.class,()->NotificationSettings.load(c,publisher));
    }
}
