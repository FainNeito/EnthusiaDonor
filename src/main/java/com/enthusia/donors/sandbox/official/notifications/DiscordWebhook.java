package com.enthusia.donors.sandbox.official.notifications;

import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;

/** Caller holds a durable claim. Only an explicit 429 rejection can be retried. */
public final class DiscordWebhook {
    public record Response(int code,String body) { }
    @FunctionalInterface public interface Transport {Response post(URI uri,String payload) throws Exception;}
    @FunctionalInterface public interface Delay {void waitMillis(long millis) throws InterruptedException;}
    private final Transport transport;private final Delay delay;
    public DiscordWebhook(Transport transport,Delay delay) {this.transport=transport;this.delay=delay;}
    public static DiscordWebhook http() {
        HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
        return new DiscordWebhook((uri,payload)->{
            var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8)).header("Content-Type","application/json").header("User-Agent","EnthusiaDonors/1.1")
                    .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofString());return new Response(response.statusCode(),response.body());
        },Thread::sleep);
    }
    public String send(String webhook,String payload) {
        try {
            URI uri=URI.create(webhook+"?wait=true");
            for(int attempt=0;attempt<4;attempt++) {
                Response response=transport.post(uri,payload);
                if(response.code()!=429)return outcome(response.code());
                if(attempt==3)return "FAILED";
                double seconds=JsonParser.parseString(response.body()).getAsJsonObject().get("retry_after").getAsDouble();
                if(!Double.isFinite(seconds) || seconds<0 || seconds>30)return "FAILED";
                delay.waitMillis(Math.max(1000,(long)Math.ceil(seconds*1000)));
            }
            return "FAILED";
        } catch(InterruptedException interrupted) {Thread.currentThread().interrupt();return "UNCERTAIN";}
        catch(Exception failure) {return "UNCERTAIN";}
    }
    public static String outcome(int code) {return code>=200 && code<300?"SENT":code==0 || code>=500?"UNCERTAIN":"FAILED";}
}
