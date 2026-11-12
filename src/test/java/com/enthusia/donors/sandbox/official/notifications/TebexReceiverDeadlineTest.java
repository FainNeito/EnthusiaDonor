package com.enthusia.donors.sandbox.official.notifications;

import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class TebexReceiverDeadlineTest {
    @Test void stalledBodiesReleaseBothWorkers() throws Exception {
        try(var receiver=new TebexHttpReceiver(new InetSocketAddress("127.0.0.1",0),new TebexWebhookEndpoint("fixture-secret",null),Duration.ofMillis(200))) {
            receiver.start();
            try(var first=stall(receiver.port());var second=stall(receiver.port())) {
                assertEquals(-1,first.getInputStream().read());
                assertEquals(-1,second.getInputStream().read());
            }
            var request=java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+receiver.port()+"/tebex")).timeout(Duration.ofSeconds(3)).POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}")).build();
            try(var client=java.net.http.HttpClient.newHttpClient()) {
                assertEquals(403,client.send(request,java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode());
            }
        }
    }
    private Socket stall(int port) throws Exception {
        Socket socket=new Socket("127.0.0.1",port);socket.setSoTimeout(3000);
        socket.getOutputStream().write(("POST /tebex HTTP/1.1\r\nHost: localhost\r\nContent-Length: 100\r\nX-Signature: "+"0".repeat(64)+"\r\n\r\n{").getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();return socket;
    }
}
