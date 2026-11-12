package com.enthusia.donors.tebex;

import com.enthusia.donors.config.DonorsConfig;
import com.enthusia.donors.sandbox.official.notifications.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.logging.Logger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.jupiter.api.Assertions.*;

class TebexWebhookEndToEndTest {
    @Test void signedEventResolvesOfficialPaymentAndEnrichesOneDurableJob(@TempDir Path dir) throws Exception {
        var clock=Instant.now().minusSeconds(1);var baseline=clock.minusSeconds(5);
        var fixture=HttpServer.create(new InetSocketAddress("127.0.0.1",0),4);
        fixture.createContext("/payments/tbx-fixture", exchange->{
            try(exchange) {
                if(!"fixture-key".equals(exchange.getRequestHeaders().getFirst("X-Tebex-Secret"))) {exchange.sendResponseHeaders(403,-1);return;}
                byte[] body=("{\"id\":42,\"amount\":\"10.00\",\"status\":\"Complete\",\"date\":\""+clock+"\",\"player\":{\"uuid\":\"365bfa21803249ee9b634fe890c9d43f\",\"name\":\"Donor\"},\"packages\":[{\"id\":1,\"name\":\"Devotee\"}]}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
            }
        });fixture.start();
        try {
            var client=new TebexClient(Logger.getAnonymousLogger(),URI.create("http://127.0.0.1:"+fixture.getAddress().getPort()+"/payments"));
            var config=new DonorsConfig("fixture-key",10,5,1,3,"tebex_payments_only",false,false,false,false,Set.of(),Set.of(),Set.of(),Set.of(),ZoneId.of("UTC"),"$",true,10,"None","$0.00","-",true,false,Path.of("unused"),false,"","","","","","","",false,true,false,1);
            var store=new NotificationStore(()->DriverManager.getConnection("jdbc:sqlite:"+dir.resolve("integration.db")),NotificationStore.Dialect.SQLITE,"fixture");
            store.init();store.observe(List.of(),baseline,p->true,p->null);
            var settings=new NotificationSettings(true,true,true,"","https://store.enthusia.info",false,false);
            var renderer=(WebhookNotificationBridge.Renderer)(p,event)->new NotificationStore.Payload(null,PaymentMessages.discord(p,settings,event.kind()==TebexWebhookEvidence.Kind.RENEWAL));
            try(var receiver=TebexWebhookAssembly.loopback(new InetSocketAddress("127.0.0.1",0),"synthetic-signing-secret-at-least-32-bytes",store,client,config,p->true,renderer)) {
                receiver.start();var http=HttpClient.newHttpClient();var uri=URI.create("http://127.0.0.1:"+receiver.port()+"/tebex");
                String payment="{\"transaction_id\":\"tbx-fixture\"}";
                for(String body:List.of("{\"type\":\"payment.completed\",\"subject\":"+payment+"}","{\"type\":\"recurring-payment.renewed\",\"subject\":{\"last_payment\":"+payment+"}}")) {
                    byte[] bytes=body.getBytes(StandardCharsets.UTF_8);String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                    Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec("synthetic-signing-secret-at-least-32-bytes".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
                    String signature=HexFormat.of().formatHex(mac.doFinal(hash.getBytes(StandardCharsets.US_ASCII)));
                    var request=HttpRequest.newBuilder(uri).header("X-Signature",signature).POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
                    assertEquals(200,http.send(request,HttpResponse.BodyHandlers.discarding()).statusCode());
                }
                assertTrue(store.pending().isEmpty());assertEquals(1,store.releaseDeferred(Instant.now().plusSeconds(130),120));
                var jobs=store.pending();assertEquals(1,jobs.size());assertTrue(jobs.getFirst().payload().contains("renewed a subscription"));
                assertTrue(jobs.getFirst().payload().contains("Devotee"));assertTrue(jobs.getFirst().payload().contains("store.enthusia.info"));
            }
        } finally {fixture.stop(0);}
    }
}
