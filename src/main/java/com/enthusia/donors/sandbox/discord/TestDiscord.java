package com.enthusia.donors.sandbox.discord;

import com.enthusia.donors.sandbox.TestSettings;
import com.enthusia.donors.sandbox.storage.SandboxDatabase;
import com.enthusia.donors.sandbox.ui.Broadcasts;
import com.enthusia.donors.sandbox.ui.Broadcasts.Notice;
import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** There is deliberately no production webhook field or production fallback in this build. */
public final class TestDiscord implements AutoCloseable {
    public record Result(String state,String detail) { }
    private record Work(Notice notice,TestSettings settings,CompletableFuture<Result> result) { }
    private final SandboxDatabase db;
    private final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor(r -> {Thread t=new Thread(r,"EnthusiaDonors-test-discord");t.setDaemon(true);return t;});
    private final ArrayBlockingQueue<Work> queue=new ArrayBlockingQueue<>(100);
    private final AtomicBoolean pumping=new AtomicBoolean(),failNext=new AtomicBoolean();
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    public TestDiscord(SandboxDatabase db){this.db=db;}
    public void injectFailure(){failNext.set(true);}
    public CompletableFuture<Result> send(Notice notice,TestSettings config) {
        if(!config.discordEnabled() || config.discordUrl().isBlank())
            return CompletableFuture.completedFuture(new Result("DISABLED","No test webhook enabled; nothing was sent."));
        CompletableFuture<Result> future=new CompletableFuture<>();
        if(!queue.offer(new Work(notice,config,future))) {future.complete(new Result("FAILED","Test webhook queue is full."));return future;}
        if(pumping.compareAndSet(false,true)) executor.execute(this::next);
        return future;
    }
    private void next() {
        Work w=queue.poll();
        if(w==null) {pumping.set(false);if(!queue.isEmpty()&&pumping.compareAndSet(false,true))executor.execute(this::next);return;}
        try {
            if(!db.claim(w.notice.id(),"discord")) {w.result.complete(new Result("DUPLICATE","Already attempted; not resent.")); next();return;}
            attempt(w,0);
        }catch(Exception ex){w.result.complete(new Result("FAILED","Could not persist dispatch claim; nothing sent."));next();}
    }
    private void attempt(Work w,int retries) {
        try {
            if(failNext.getAndSet(false)) {finish(w,new Result("UNCERTAIN","Injected Discord timeout; no network request made."));return;}
            HttpRequest req=HttpRequest.newBuilder(URI.create(w.settings.discordUrl()+"?wait=true"))
                    .timeout(Duration.ofSeconds(8)).header("Content-Type","application/json")
                    .header("User-Agent","EnthusiaDonors-Test/1").POST(HttpRequest.BodyPublishers.ofString(payload(w.notice,w.settings))).build();
            HttpResponse<String> response=client.send(req,HttpResponse.BodyHandlers.ofString());
            int code=response.statusCode();
            if(code==429 && retries<3) {
                double wait=2;
                try {wait=JsonParser.parseString(response.body()).getAsJsonObject().get("retry_after").getAsDouble();}catch(Exception ignored) { }
                if(!Double.isFinite(wait)||wait<0||wait>120) {finish(w,new Result("FAILED","Rate limited beyond test retry window."));return;}
                executor.schedule(() -> attempt(w,retries+1),Math.max(1000,(long)Math.ceil(wait*1000)),TimeUnit.MILLISECONDS);return;
            }
            if(code>=200 && code<300) finish(w,new Result("SENT","Test-channel webhook accepted."));
            else if(code>=500) finish(w,new Result("UNCERTAIN","Discord HTTP "+code+"; not automatically retried."));
            else finish(w,new Result("FAILED","Discord HTTP "+code+". Check the dedicated test webhook."));
        } catch(InterruptedException ex) {Thread.currentThread().interrupt();finish(w,new Result("UNCERTAIN","Dispatch interrupted; not automatically resent."));}
        catch(Exception ex) {finish(w,new Result("UNCERTAIN","Network response unavailable; not automatically resent."));}
    }
    private void finish(Work w,Result result) {
        try {db.finish(w.notice.id(),"discord",result.state,result.detail);}catch(Exception ex) {
            result=new Result("UNCERTAIN","Could not save delivery receipt; inspect the test channel before retrying.");
        }
        w.result.complete(result); if(!executor.isShutdown()) {try {executor.execute(this::next);}catch(RejectedExecutionException ignored) { }}
    }
    public static String payload(Notice n,TestSettings settings) {
        JsonObject json=new JsonObject(); String text=Broadcasts.discordText(n,settings);
        if(text.length()>2000) throw new IllegalArgumentException("Discord test message exceeds 2,000 characters.");
        json.addProperty("content",text);
        String username=settings.discordUsername();
        if(!username.contains("[TEST]"))username+=" [TEST]";
        json.addProperty("username",username.length()>80?username.substring(0,73)+" [TEST]":username);
        JsonObject mentions=new JsonObject();mentions.add("parse",new JsonArray());json.add("allowed_mentions",mentions);
        return json.toString();
    }
    @Override public void close(){executor.shutdownNow();Work w;while((w=queue.poll())!=null)w.result.complete(new Result("CANCELLED","Plugin stopped before dispatch."));}
}
