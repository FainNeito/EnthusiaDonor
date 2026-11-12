package com.enthusia.donors.sandbox.official.notifications;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class TebexInboxConsumerTest {
    @Test void emptyClaimChecksForQuarantineOrBacklogAttention() {
        for(boolean attention:List.of(false,true)) {
            var paths=new ArrayList<String>();
            try(var consumer=new TebexInboxConsumer((path,body)->{
                paths.add(path);
                return path.endsWith("claim")?new TebexInboxConsumer.Reply(204,""):
                    new TebexInboxConsumer.Reply(200,"{\"attention\":"+attention+"}");
            },(body,signature)->{fail("No event was claimed");return null;})) {
                assertTrue(consumer.pollOnce());
                assertEquals(List.of("/consumer/claim","/consumer/status"),paths);
                assertEquals(attention?"ATTENTION":"EMPTY",consumer.status());
            }
        }
    }
    @Test void unavailableOrMalformedStatusNeverReportsEmptyAndHealthy() {
        for(var reply:List.of(new TebexInboxConsumer.Reply(503,""),new TebexInboxConsumer.Reply(200,"{}"),
                new TebexInboxConsumer.Reply(200,"{\"attention\":\"false\"}"))) {
            try(var consumer=new TebexInboxConsumer((path,body)->path.endsWith("claim")?new TebexInboxConsumer.Reply(204,""):reply,
                    (body,signature)->{fail("No event was claimed");return null;})) {
                assertFalse(consumer.pollOnce());assertEquals("RETRY",consumer.status());
            }
        }
    }
    private static String claim() throws Exception {
        byte[] body="synthetic".getBytes(StandardCharsets.UTF_8);
        JsonObject json=new JsonObject();
        json.addProperty("id",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)));
        json.addProperty("body",Base64.getEncoder().encodeToString(body));
        json.addProperty("signature","a".repeat(64));json.addProperty("lease_token","b".repeat(64));
        return json.toString();
    }
    @Test void ackWaitsForProcessingCommit() throws Exception {
        var committed=new CompletableFuture<TebexWebhookEndpoint.Response>();
        var entered=new CountDownLatch(1);var ack=new CountDownLatch(1);
        try(var consumer=new TebexInboxConsumer((path,body)->{
            if(path.endsWith("ack")) {ack.countDown();return new TebexInboxConsumer.Reply(204,"");}
            return new TebexInboxConsumer.Reply(200,claim());
        },(body,signature)->{entered.countDown();return committed;})) {
            var thread=new Thread(consumer::pollOnce);thread.start();
            assertTrue(entered.await(2,TimeUnit.SECONDS));assertFalse(ack.await(50,TimeUnit.MILLISECONDS));
            committed.complete(new TebexWebhookEndpoint.Response(200,"{}"));
            thread.join(2000);assertFalse(thread.isAlive());assertEquals(0,ack.getCount());
        }
    }
    @Test void processingFailureOrInvalidEnvelopeNeverAcknowledges() throws Exception {
        for(int status:List.of(400,403,503)) {
            var paths=new ArrayList<String>();
            try(var consumer=new TebexInboxConsumer((path,body)->{paths.add(path);return new TebexInboxConsumer.Reply(200,claim());},
                    (body,signature)->CompletableFuture.completedFuture(new TebexWebhookEndpoint.Response(status,"")))) {
                assertFalse(consumer.pollOnce());assertEquals(List.of("/consumer/claim"),paths);
            }
        }
        try(var consumer=new TebexInboxConsumer((path,body)->new TebexInboxConsumer.Reply(200,claim().replace("c3ludGhldGlj","Y2hhbmdlZA==")),
                (body,signature)->{fail("Tampered body reached processor");return null;})) {assertFalse(consumer.pollOnce());}
    }
    @Test void closeDuringProcessingPreventsAck() throws Exception {
        var completion=new CompletableFuture<TebexWebhookEndpoint.Response>();var entered=new CountDownLatch(1);
        var consumer=new TebexInboxConsumer((path,body)->{assertEquals("/consumer/claim",path);return new TebexInboxConsumer.Reply(200,claim());},
                (body,signature)->{entered.countDown();return completion;});
        var thread=new Thread(consumer::pollOnce);thread.start();assertTrue(entered.await(2,TimeUnit.SECONDS));
        consumer.close();completion.complete(new TebexWebhookEndpoint.Response(200,"{}"));thread.join(2000);
        assertFalse(thread.isAlive());assertFalse(consumer.pollOnce());
    }
    @Test void onlyFixedHttpsOriginWithNoCredentialsPathOrQueryIsAllowed() {
        for(String uri:List.of("http://inbox.example","https://user:password@inbox.example","https://inbox.example/path","https://inbox.example?token=x","https://inbox.example#fragment"))
            assertThrows(IllegalArgumentException.class,()->TebexInboxConsumer.validateOrigin(URI.create(uri)));
        assertDoesNotThrow(()->TebexInboxConsumer.validateOrigin(URI.create("https://inbox.example")));
    }
    @Test void lostAckThenRedeliveryUsesOneDurableOutboxJob(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var now=java.time.Instant.now();String secret="synthetic-signing-secret-at-least-32-bytes";
        byte[] body="{\"type\":\"payment.completed\",\"subject\":{\"transaction_id\":\"fixture\"}}".getBytes(StandardCharsets.UTF_8);
        String id=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        String signature=HexFormat.of().formatHex(mac.doFinal(id.getBytes(StandardCharsets.US_ASCII)));
        var store=new NotificationStore(()->java.sql.DriverManager.getConnection("jdbc:sqlite:"+dir.resolve("outbox.db")),NotificationStore.Dialect.SQLITE,"fixture");
        store.init();store.observe(List.of(),now.minusSeconds(2),p->true,p->null);
        var payment=new com.enthusia.donors.model.PaymentRecord("official-hash",UUID.randomUUID(),"Donor",java.math.BigDecimal.TEN,"USD","complete",now,false,false,List.of(1),now);
        var bridge=new WebhookNotificationBridge(store,tx->CompletableFuture.completedFuture(payment),p->true,
                (p,event)->new NotificationStore.Payload(null,"synthetic-discord"),true);
        var endpoint=new TebexWebhookEndpoint(secret,bridge);var acks=new java.util.concurrent.atomic.AtomicInteger();
        JsonObject envelope=new JsonObject();envelope.addProperty("id",id);envelope.addProperty("body",Base64.getEncoder().encodeToString(body));
        envelope.addProperty("signature",signature);envelope.addProperty("lease_token","a".repeat(64));
        try(var consumer=new TebexInboxConsumer((path,input)->{
            if(path.endsWith("claim"))return new TebexInboxConsumer.Reply(200,envelope.toString());
            if(acks.incrementAndGet()==1)throw new java.io.IOException("Synthetic lost ACK response");
            return new TebexInboxConsumer.Reply(204,"");
        },(bytes,signed)->endpoint.handle(bytes,signed,now.plusSeconds(1)))) {
            assertFalse(consumer.pollOnce());
            envelope.addProperty("lease_token","b".repeat(64)); // lease expiry/redelivery is tested in local D1
            assertTrue(consumer.pollOnce());assertEquals(2,acks.get());
            assertEquals(1,store.releaseDeferred(now.plusSeconds(130),120));assertEquals(1,store.pending().size());
        }
    }
}
