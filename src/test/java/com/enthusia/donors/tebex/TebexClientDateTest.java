package com.enthusia.donors.tebex;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class TebexClientDateTest {
    @Test void invalidPaymentDateCannotBecomeCurrentMonthSupport() throws Exception {
        TebexClient client = new TebexClient(Logger.getAnonymousLogger());
        JsonObject payment = new JsonObject();
        payment.addProperty("id", 42);
        payment.addProperty("amount", "20.00");
        payment.addProperty("status", "Complete");
        payment.addProperty("date", "not-a-date");
        JsonObject player = new JsonObject();
        player.addProperty("uuid", "365bfa21803249ee9b634fe890c9d43f");
        player.addProperty("name", "Donor");
        payment.add("player", player);

        assertThrows(TebexClient.TebexException.class,
                () -> client.parsePayment(payment, Instant.parse("2026-09-29T12:00:00Z")));
        payment.addProperty("date", "2026-08-03T12:00:00+00:00");
        assertEquals(Instant.parse("2026-08-03T12:00:00Z"),
                client.parsePayment(payment, Instant.parse("2026-09-29T12:00:00Z")).orElseThrow().createdAt());
    }
    @Test void paymentRetainsOfficialPackageNamesAlongsideIds() throws Exception {
        var payment = com.google.gson.JsonParser.parseString("""
                {"id":42,"amount":"20.00","status":"Complete","date":"2026-08-03T12:00:00Z",
                 "player":{"uuid":"365bfa21803249ee9b634fe890c9d43f","name":"Donor"},
                 "packages":[{"id":1,"name":"Avid"},{"id":2,"name":"Devotee"}]}
                """).getAsJsonObject();
        var parsed = new TebexClient(Logger.getAnonymousLogger()).parsePayment(payment, Instant.EPOCH).orElseThrow();
        assertEquals(java.util.List.of(1, 2), parsed.packageIds());
        assertEquals(java.util.List.of("Avid", "Devotee"), parsed.packageNames());
    }
    @Test void transactionDetailResolvesToSameIdentityAsPolledPayment() throws Exception {
        var json = com.google.gson.JsonParser.parseString("""
                {"id":42,"amount":"20.00","status":"Complete","date":"2026-08-03T12:00:00Z",
                 "player":{"uuid":"365bfa21803249ee9b634fe890c9d43f","name":"Donor"},"packages":[]}
                """).getAsJsonObject();
        var client=new TebexClient(Logger.getAnonymousLogger());
        assertEquals(client.parsePayment(json,Instant.EPOCH).orElseThrow().paymentIdHash(),client.resolvedPaymentIdHash(json));
        assertThrows(TebexClient.TebexException.class,()->client.resolvedPaymentIdHash(new JsonObject()));
    }
    @Test void resolvedWebhookAndPollShareDurableNotificationIdentity(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var json=com.google.gson.JsonParser.parseString("""
                {"id":42,"amount":"20.00","status":"Complete","date":"2026-08-03T12:00:01Z",
                 "player":{"uuid":"365bfa21803249ee9b634fe890c9d43f","name":"Donor"},"packages":[]}
                """).getAsJsonObject();
        var client=new TebexClient(Logger.getAnonymousLogger());
        var poll=client.parsePayment(json,Instant.EPOCH).orElseThrow();
        var detailHash=client.resolvedPaymentIdHash(json);
        var fromWebhook=new com.enthusia.donors.model.PaymentRecord(detailHash,poll.playerUuid(),poll.playerName(),poll.amount(),poll.currency(),poll.status(),poll.createdAt(),false,false,poll.packageIds(),poll.importedAt());
        for (boolean webhookFirst : java.util.List.of(false,true)) {
            var db=dir.resolve(webhookFirst?"webhook-first.db":"poll-first.db");
            var store=new com.enthusia.donors.sandbox.official.notifications.NotificationStore(
                    ()->java.sql.DriverManager.getConnection("jdbc:sqlite:"+db),
                    com.enthusia.donors.sandbox.official.notifications.NotificationStore.Dialect.SQLITE,"fixture");
            store.init(); var base=Instant.parse("2026-08-03T12:00:00Z");
            java.util.function.Function<com.enthusia.donors.model.PaymentRecord,com.enthusia.donors.sandbox.official.notifications.NotificationStore.Payload> render = p -> new com.enthusia.donors.sandbox.official.notifications.NotificationStore.Payload("chat","discord");
            store.observe(java.util.List.of(),base,p->true,render);
            store.observe(java.util.List.of(webhookFirst?fromWebhook:poll),base.plusSeconds(2),p->true,render);
            store.observe(java.util.List.of(webhookFirst?poll:fromWebhook),base.plusSeconds(3),p->true,render);
            assertEquals(2,store.pending().size());
        }
    }
}
