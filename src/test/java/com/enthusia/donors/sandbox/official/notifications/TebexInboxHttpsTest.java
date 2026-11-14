package com.enthusia.donors.sandbox.official.notifications;

import com.sun.net.httpserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.net.ssl.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class TebexInboxHttpsTest {
    @Test void verifiesTlsDoesNotRedirectAndBoundsResponse(@TempDir Path dir) throws Exception {
        String password="synthetic-password";Path keys=dir.resolve("fixture.p12");
        String keytool=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"keytool.exe":"keytool").toString();
        var generated=new ProcessBuilder(keytool,"-genkeypair","-alias","fixture","-keyalg","RSA","-keysize","2048",
                "-storetype","PKCS12","-keystore",keys.toString(),"-storepass",password,"-keypass",password,
                "-dname","CN=localhost","-ext","SAN=dns:localhost","-validity","2","-noprompt")
                .redirectErrorStream(true).redirectOutput(dir.resolve("keytool.log").toFile()).start();
        assertTrue(generated.waitFor(20,TimeUnit.SECONDS));assertEquals(0,generated.exitValue());
        var store=KeyStore.getInstance("PKCS12");try(var input=Files.newInputStream(keys)) {store.load(input,password.toCharArray());}
        var km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(store,password.toCharArray());
        var tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(store);
        var tls=SSLContext.getInstance("TLS");tls.init(km.getKeyManagers(),tm.getTrustManagers(),null);
        var server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),4);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        var executor=Executors.newCachedThreadPool();server.setExecutor(executor);
        var mode=new AtomicInteger();var redirected=new AtomicInteger();var authorized=new AtomicBoolean();
        String token="synthetic-consumer-token-at-least-32-bytes";
        server.createContext("/consumer/claim",exchange->{try(exchange) {
            authorized.set(("Bearer "+token).equals(exchange.getRequestHeaders().getFirst("Authorization")));
            exchange.getRequestBody().readAllBytes();
            if(mode.get()==1) {exchange.getResponseHeaders().set("Location","/trap");exchange.sendResponseHeaders(307,-1);}
            else if(mode.get()==2) {exchange.sendResponseHeaders(200,350001);exchange.getResponseBody().write(new byte[350001]);}
            else if(mode.get()==3) {exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write(1);exchange.getResponseBody().flush();
                try {Thread.sleep(12000);} catch(InterruptedException stopped) {Thread.currentThread().interrupt();}}
            else exchange.sendResponseHeaders(204,-1);
        }});
        server.createContext("/trap",exchange->{try(exchange) {redirected.incrementAndGet();exchange.sendResponseHeaders(204,-1);}});
        server.start();
        URI origin=URI.create("https://localhost:"+server.getAddress().getPort());
        try(var untrusted=HttpClient.newHttpClient();var trusted=HttpClient.newBuilder().sslContext(tls).build()) {
            var defaultTransport=new TebexInboxConsumer.HttpTransport(origin,token,untrusted);
            assertThrows(Exception.class,()->defaultTransport.post("/consumer/claim","{}"));
            var mismatch=new TebexInboxConsumer.HttpTransport(URI.create("https://127.0.0.1:"+origin.getPort()),token,trusted);
            assertThrows(Exception.class,()->mismatch.post("/consumer/claim","{}"));
            var transport=new TebexInboxConsumer.HttpTransport(origin,token,trusted);
            assertEquals(204,transport.post("/consumer/claim","{}").status());assertTrue(authorized.get());
            mode.set(1);assertEquals(307,transport.post("/consumer/claim","{}").status());assertEquals(0,redirected.get());
            mode.set(2);assertThrows(Exception.class,()->transport.post("/consumer/claim","{}"));
            mode.set(3);long start=System.nanoTime();assertThrows(Exception.class,()->transport.post("/consumer/claim","{}"));
            assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-start)<12,"Body read must share the request deadline");
        } finally {server.stop(0);executor.shutdownNow();}
    }
}
