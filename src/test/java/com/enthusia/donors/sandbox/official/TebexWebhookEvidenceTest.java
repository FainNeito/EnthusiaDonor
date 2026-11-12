package com.enthusia.donors.sandbox.official;

import com.enthusia.donors.sandbox.official.notifications.TebexWebhookEvidence;
import org.junit.jupiter.api.Test;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

class TebexWebhookEvidenceTest {
    private static final String SECRET = "fixture-secret";
    private String signature(byte[] body) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(hash.getBytes(StandardCharsets.US_ASCII)));
    }
    private byte[] body(String type, String payment) {
        return ("{\"type\":\"" + type + "\",\"subject\":" + payment + "}").getBytes(StandardCharsets.UTF_8);
    }
    private static final String PAYMENT = "{\"transaction_id\":\"tbx-fixture\",\"customer\":{\"username\":{\"id\":\"one\",\"username\":\"Buyer\"}},\"products\":[{\"name\":\"Avid\",\"username\":{\"id\":\"two\",\"username\":\"Recipient\"}}]}";
    @Test void renewalAndPaymentExposeSameTransactionForFutureDeduplication() throws Exception {
        byte[] a=body("payment.completed",PAYMENT), b=body("recurring-payment.renewed","{\"last_payment\":"+PAYMENT+"}");
        var payment=TebexWebhookEvidence.parse(a,signature(a),SECRET).orElseThrow();
        var renewal=TebexWebhookEvidence.parse(b,signature(b),SECRET).orElseThrow();
        assertEquals(payment.transactionId(),renewal.transactionId());
        assertEquals(TebexWebhookEvidence.Kind.RENEWAL,renewal.kind());
        assertEquals("Buyer",payment.customerName()); assertEquals("Recipient",payment.products().getFirst().recipientName());
    }
    @Test void rejectsTamperingUnsupportedTypesAndMissingTransaction() throws Exception {
        byte[] good=body("payment.completed",PAYMENT), changed=body("payment.completed",PAYMENT.replace("Avid","Other"));
        assertTrue(TebexWebhookEvidence.parse(changed,signature(good),SECRET).isEmpty());
        byte[] cancelled=body("recurring-payment.ended","{}");
        assertTrue(TebexWebhookEvidence.parse(cancelled,signature(cancelled),SECRET).isEmpty());
        byte[] noId=body("recurring-payment.renewed","{\"last_payment\":{}}");
        assertTrue(TebexWebhookEvidence.parse(noId,signature(noId),SECRET).isEmpty());
    }
    @Test void bridgeAndPollingShareOneOutboxForBothOrders(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var when=java.time.Instant.parse("2026-11-10T12:00:00Z");
        var p=new com.enthusia.donors.model.PaymentRecord("same-hash",java.util.UUID.randomUUID(),"Donor",java.math.BigDecimal.TEN,"USD","complete",when.plusSeconds(1),false,false,java.util.List.of(1),when);
        byte[] webhook=body("recurring-payment.renewed","{\"last_payment\":"+PAYMENT+"}");
        for(boolean webhookFirst : java.util.List.of(false,true)) {
            var db=dir.resolve(webhookFirst?"first.db":"last.db");
            var store=new com.enthusia.donors.sandbox.official.notifications.NotificationStore(()->java.sql.DriverManager.getConnection("jdbc:sqlite:"+db),com.enthusia.donors.sandbox.official.notifications.NotificationStore.Dialect.SQLITE,"store");
            store.init();
            java.util.function.Function<com.enthusia.donors.model.PaymentRecord,com.enthusia.donors.sandbox.official.notifications.NotificationStore.Payload> render=x->new com.enthusia.donors.sandbox.official.notifications.NotificationStore.Payload("chat","discord");
            store.observe(java.util.List.of(),when,x->true,render);
            var bridge=new com.enthusia.donors.sandbox.official.notifications.WebhookNotificationBridge(store,tx->java.util.concurrent.CompletableFuture.completedFuture(p),x->true,(x,event)->render.apply(x));
            if(webhookFirst) bridge.accept(webhook,signature(webhook),SECRET,when.plusSeconds(2)).toCompletableFuture().join();
            store.observe(java.util.List.of(p),when.plusSeconds(3),x->true,render);
            bridge.accept(webhook,signature(webhook),SECRET,when.plusSeconds(4)).toCompletableFuture().join();
            assertEquals(2,store.pending().size());
        }
    }
    @Test void invalidSignatureNeverInvokesLookup(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var store=new com.enthusia.donors.sandbox.official.notifications.NotificationStore(()->java.sql.DriverManager.getConnection("jdbc:sqlite:"+dir.resolve("invalid.db")),com.enthusia.donors.sandbox.official.notifications.NotificationStore.Dialect.SQLITE,"store");
        store.init(); var calls=new java.util.concurrent.atomic.AtomicInteger();
        var bridge=new com.enthusia.donors.sandbox.official.notifications.WebhookNotificationBridge(store,tx->{calls.incrementAndGet();return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException());},x->true,(x,e)->null);
        assertThrows(java.util.concurrent.CompletionException.class,()->bridge.accept(body("payment.completed",PAYMENT),"0".repeat(64),SECRET,java.time.Instant.now()).toCompletableFuture().join());
        assertEquals(0,calls.get()); assertTrue(store.pending().isEmpty());
    }
    @Test void concurrentWebhookAndPollProduceOnlyOneJobPerChannel(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var when=java.time.Instant.parse("2026-11-10T12:00:00Z");
        var p=new com.enthusia.donors.model.PaymentRecord("same-hash",java.util.UUID.randomUUID(),"Donor",java.math.BigDecimal.TEN,"USD","complete",when.plusSeconds(1),false,false,java.util.List.of(1),when);
        var store=new com.enthusia.donors.sandbox.official.notifications.NotificationStore(()->java.sql.DriverManager.getConnection("jdbc:sqlite:"+dir.resolve("race.db")),com.enthusia.donors.sandbox.official.notifications.NotificationStore.Dialect.SQLITE,"store");
        store.init();
        java.util.function.Function<com.enthusia.donors.model.PaymentRecord,com.enthusia.donors.sandbox.official.notifications.NotificationStore.Payload> render=x->new com.enthusia.donors.sandbox.official.notifications.NotificationStore.Payload("chat","discord");
        store.observe(java.util.List.of(),when,x->true,render);
        byte[] webhook=body("recurring-payment.renewed","{\"last_payment\":"+PAYMENT+"}");
        String signed=signature(webhook);
        var bridge=new com.enthusia.donors.sandbox.official.notifications.WebhookNotificationBridge(store,tx->java.util.concurrent.CompletableFuture.completedFuture(p),x->true,(x,e)->render.apply(x));
        var gate=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var one=pool.submit(()->{gate.await();return store.observe(java.util.List.of(p),when.plusSeconds(3),x->true,render);});
            var two=pool.submit(()->{gate.await();return bridge.accept(webhook,signed,SECRET,when.plusSeconds(3)).toCompletableFuture().join();});
            gate.countDown(); assertEquals(2,one.get()+two.get());
        }
        assertEquals(2,store.pending().size());
    }
    @Test void webhookBeforeBaselineFailsWithoutConsumingPayment(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var when=java.time.Instant.parse("2026-11-10T12:00:00Z");
        var payment=new com.enthusia.donors.model.PaymentRecord("hash",java.util.UUID.randomUUID(),"Donor",java.math.BigDecimal.TEN,"USD","complete",when.plusSeconds(1),false,false,java.util.List.of(1),when);
        var store=new com.enthusia.donors.sandbox.official.notifications.NotificationStore(()->java.sql.DriverManager.getConnection("jdbc:sqlite:"+dir.resolve("baseline.db")),com.enthusia.donors.sandbox.official.notifications.NotificationStore.Dialect.SQLITE,"store");
        store.init(); byte[] webhook=body("payment.completed",PAYMENT);
        var bridge=new com.enthusia.donors.sandbox.official.notifications.WebhookNotificationBridge(store,tx->java.util.concurrent.CompletableFuture.completedFuture(payment),x->true,(x,e)->new com.enthusia.donors.sandbox.official.notifications.NotificationStore.Payload("chat","discord"));
        assertThrows(java.util.concurrent.CompletionException.class,()->bridge.accept(webhook,signature(webhook),SECRET,when.plusSeconds(2)).toCompletableFuture().join());
        assertTrue(store.pending().isEmpty());
        store.observe(java.util.List.of(),when,x->true,x->new com.enthusia.donors.sandbox.official.notifications.NotificationStore.Payload("chat","discord"));
        assertEquals(2,bridge.accept(webhook,signature(webhook),SECRET,when.plusSeconds(2)).toCompletableFuture().join());
    }
    @Test void endpointValidatesSignatureAndHandshake(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var store=new com.enthusia.donors.sandbox.official.notifications.NotificationStore(()->java.sql.DriverManager.getConnection("jdbc:sqlite:"+dir.resolve("handshake.db")),com.enthusia.donors.sandbox.official.notifications.NotificationStore.Dialect.SQLITE,"store");
        store.init(); var calls=new java.util.concurrent.atomic.AtomicInteger();
        var bridge=new com.enthusia.donors.sandbox.official.notifications.WebhookNotificationBridge(store,tx->{calls.incrementAndGet();return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException());},x->true,(x,e)->null);
        var endpoint=new com.enthusia.donors.sandbox.official.notifications.TebexWebhookEndpoint(SECRET,bridge);
        byte[] validation="{\"type\":\"validation.webhook\",\"id\":\"abc-123\"}".getBytes(StandardCharsets.UTF_8);
        assertEquals(403,endpoint.handle(validation,"0".repeat(64),java.time.Instant.now()).toCompletableFuture().join().status());
        var ok=endpoint.handle(validation,signature(validation),java.time.Instant.now()).toCompletableFuture().join();
        assertEquals(200,ok.status());assertEquals("abc-123",com.google.gson.JsonParser.parseString(ok.body()).getAsJsonObject().get("id").getAsString());assertEquals(0,calls.get());
        byte[] purchase=body("payment.completed",PAYMENT);
        assertEquals(503,endpoint.handle(purchase,signature(purchase),java.time.Instant.now()).toCompletableFuture().join().status());
        assertEquals(1,calls.get());
    }
    @Test void loopbackReceiverAcceptsSignedValidationAndRejectsBadRequests(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var store=new com.enthusia.donors.sandbox.official.notifications.NotificationStore(()->java.sql.DriverManager.getConnection("jdbc:sqlite:"+dir.resolve("http.db")),com.enthusia.donors.sandbox.official.notifications.NotificationStore.Dialect.SQLITE,"store");
        store.init();
        var bridge=new com.enthusia.donors.sandbox.official.notifications.WebhookNotificationBridge(store,tx->java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException()),x->true,(x,e)->null);
        var endpoint=new com.enthusia.donors.sandbox.official.notifications.TebexWebhookEndpoint(SECRET,bridge);
        assertThrows(IllegalArgumentException.class,()->new com.enthusia.donors.sandbox.official.notifications.TebexHttpReceiver(new java.net.InetSocketAddress("0.0.0.0",0),endpoint));
        try(var receiver=new com.enthusia.donors.sandbox.official.notifications.TebexHttpReceiver(new java.net.InetSocketAddress("127.0.0.1",0),endpoint)) {
            receiver.start();var http=java.net.http.HttpClient.newHttpClient();
            var uri=java.net.URI.create("http://127.0.0.1:"+receiver.port()+"/tebex");
            byte[] validation="{\"type\":\"validation.webhook\",\"id\":\"abc-123\"}".getBytes(StandardCharsets.UTF_8);
            var valid=java.net.http.HttpRequest.newBuilder(uri).header("X-Signature",signature(validation)).POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(validation)).build();
            assertEquals(200,http.send(valid,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
            var forged=java.net.http.HttpRequest.newBuilder(uri).header("X-Signature","0".repeat(64)).POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(validation)).build();
            assertEquals(403,http.send(forged,java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(405,http.send(java.net.http.HttpRequest.newBuilder(uri).GET().build(),java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode());
        }
    }
    @Test void signedHttpReplayCreatesOneOutboxJobPerChannel(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var now=java.time.Instant.now();
        var p=new com.enthusia.donors.model.PaymentRecord("http-fixture",java.util.UUID.randomUUID(),"Donor",java.math.BigDecimal.TEN,"USD","complete",now,false,false,java.util.List.of(1),now);
        var store=new com.enthusia.donors.sandbox.official.notifications.NotificationStore(()->java.sql.DriverManager.getConnection("jdbc:sqlite:"+dir.resolve("http-replay.db")),com.enthusia.donors.sandbox.official.notifications.NotificationStore.Dialect.SQLITE,"store");
        store.init();store.observe(java.util.List.of(),now.minusSeconds(2),x->true,x->null);
        var bridge=new com.enthusia.donors.sandbox.official.notifications.WebhookNotificationBridge(store,tx->java.util.concurrent.CompletableFuture.completedFuture(p),x->true,(x,e)->new com.enthusia.donors.sandbox.official.notifications.NotificationStore.Payload("chat","discord"));
        var endpoint=new com.enthusia.donors.sandbox.official.notifications.TebexWebhookEndpoint(SECRET,bridge);
        try(var receiver=new com.enthusia.donors.sandbox.official.notifications.TebexHttpReceiver(new java.net.InetSocketAddress("127.0.0.1",0),endpoint)) {
            receiver.start();var http=java.net.http.HttpClient.newHttpClient();
            var uri=java.net.URI.create("http://127.0.0.1:"+receiver.port()+"/tebex");
            byte[] event=body("payment.completed",PAYMENT);
            var request=java.net.http.HttpRequest.newBuilder(uri).header("X-Signature",signature(event)).POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(event)).build();
            assertEquals(200,http.send(request,java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(200,http.send(request,java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(2,store.pending().size());
        }
    }
    @Test void deferredPaymentAndRenewalPreserveRenewalInEitherOrder(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var when=java.time.Instant.parse("2026-11-10T12:00:00Z");
        var payment=new com.enthusia.donors.model.PaymentRecord("same-hash",java.util.UUID.randomUUID(),"Donor",java.math.BigDecimal.TEN,"USD","complete",when.plusSeconds(1),false,false,java.util.List.of(1),when);
        byte[] purchase=body("payment.completed",PAYMENT), renewal=body("recurring-payment.renewed","{\"last_payment\":"+PAYMENT+"}");
        for(boolean renewalFirst : java.util.List.of(false,true)) {
            var db=dir.resolve(renewalFirst?"renewal.db":"payment.db");
            var store=new com.enthusia.donors.sandbox.official.notifications.NotificationStore(()->java.sql.DriverManager.getConnection("jdbc:sqlite:"+db),com.enthusia.donors.sandbox.official.notifications.NotificationStore.Dialect.SQLITE,"store");
            store.init();store.observe(java.util.List.of(),when,x->true,x->null);
            var bridge=new com.enthusia.donors.sandbox.official.notifications.WebhookNotificationBridge(store,tx->java.util.concurrent.CompletableFuture.completedFuture(payment),x->true,(x,e)->new com.enthusia.donors.sandbox.official.notifications.NotificationStore.Payload(e.kind().name(),e.kind().name()),true);
            byte[] first=renewalFirst?renewal:purchase,second=renewalFirst?purchase:renewal;
            bridge.accept(first,signature(first),SECRET,when.plusSeconds(2)).toCompletableFuture().join();
            bridge.accept(second,signature(second),SECRET,when.plusSeconds(3)).toCompletableFuture().join();
            bridge.accept(renewal,signature(renewal),SECRET,when.plusSeconds(4)).toCompletableFuture().join();
            assertTrue(store.pending().isEmpty());assertEquals(2,store.releaseDeferred(when.plusSeconds(32),30));
            assertEquals(2,store.pending().size());assertTrue(store.pending().stream().allMatch(j->j.payload().equals("RENEWAL")));
        }
    }
}
