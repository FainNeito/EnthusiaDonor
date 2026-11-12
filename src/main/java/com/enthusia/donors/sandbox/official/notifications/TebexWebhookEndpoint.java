package com.enthusia.donors.sandbox.official.notifications;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Transport-independent request adapter. It does not bind a network socket. */
public final class TebexWebhookEndpoint {
    public record Response(int status, String body) { }
    private final String secret;
    private final WebhookNotificationBridge bridge;
    public TebexWebhookEndpoint(String secret, WebhookNotificationBridge bridge) {
        if(secret==null || secret.isBlank())throw new IllegalArgumentException("Webhook secret required");
        this.secret=secret;this.bridge=bridge;
    }
    public CompletionStage<Response> handle(byte[] body,String signature,Instant now) {
        if(!TebexWebhookEvidence.verify(body,signature,secret))return CompletableFuture.completedFuture(new Response(403,""));
        final JsonObject request;
        try {request=JsonParser.parseString(new String(body,StandardCharsets.UTF_8)).getAsJsonObject();}
        catch(Exception invalid) {return CompletableFuture.completedFuture(new Response(400,""));}
        if(request.has("type") && request.get("type").isJsonPrimitive()
                && "validation.webhook".equals(request.get("type").getAsString())) {
            try {
                String id=request.get("id").getAsString();
                if(!id.matches("[A-Za-z0-9-]{1,128}"))return CompletableFuture.completedFuture(new Response(400,""));
                JsonObject response=new JsonObject();response.addProperty("id",id);
                return CompletableFuture.completedFuture(new Response(200,response.toString()));
            } catch(Exception invalid) {return CompletableFuture.completedFuture(new Response(400,""));}
        }
        if(TebexWebhookEvidence.parse(body,signature,secret).isEmpty())return CompletableFuture.completedFuture(new Response(400,""));
        try {
            return bridge.accept(body,signature,secret,now).handle((count,error)->
                    error==null?new Response(200,"{}"):new Response(503,""));
        } catch(Exception failure) {return CompletableFuture.completedFuture(new Response(503,""));}
    }
}
