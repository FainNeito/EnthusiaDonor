package com.enthusia.donors.sandbox.official.notifications;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Outbound-only, one leased event at a time. No announcement or fallback bypass. */
public final class TebexInboxConsumer implements AutoCloseable {
    record Reply(int status,String body) { }
    interface Transport { Reply post(String path,String body) throws Exception; }
    interface Processor { CompletionStage<TebexWebhookEndpoint.Response> process(byte[] body,String signature); }
    private final Transport transport;
    private final Processor processor;
    private final ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor(r->{
        var thread=new Thread(r,"EnthusiaDonors-inbox");thread.setDaemon(true);return thread;
    });
    private final AtomicBoolean started=new AtomicBoolean();
    private volatile boolean closed;
    private volatile String status="DISABLED";
    private int failures;
    public TebexInboxConsumer(URI origin,String token,TebexWebhookEndpoint endpoint) {
        this(new HttpTransport(origin,token), (body,signature)->endpoint.handle(body,signature,Instant.now()));
    }
    TebexInboxConsumer(Transport transport,Processor processor) {this.transport=transport;this.processor=processor;}
    public void start() {
        if(!closed && started.compareAndSet(false,true))try {scheduler.execute(this::tick);}
        catch(RejectedExecutionException stopped) { /* concurrent shutdown */ }
    }
    private void tick() {
        if(closed)return;
        boolean success=pollOnce();failures=success?0:Math.min(failures+1,4);
        long delay=Math.min(60,5L<<failures)+ThreadLocalRandom.current().nextLong(3);
        if(!closed)try {scheduler.schedule(this::tick,delay,TimeUnit.SECONDS);} catch(RejectedExecutionException stopped) { /* shutdown */ }
    }
    boolean pollOnce() {
        if(closed)return false;
        try {
            Reply reply=transport.post("/consumer/claim","{}");
            if(reply.status()==204) {
                // No claimable event does not mean healthy: quarantine and an
                // exhausted backlog require attention without automatic replay.
                Reply health=transport.post("/consumer/status","{}");
                if(health.status()!=200 || health.body().length()>4096)throw new IllegalStateException();
                var attention=JsonParser.parseString(health.body()).getAsJsonObject().get("attention");
                if(attention==null || !attention.isJsonPrimitive() || !attention.getAsJsonPrimitive().isBoolean())throw new IllegalStateException();
                status=attention.getAsBoolean()?"ATTENTION":"EMPTY";return true;
            }
            if(reply.status()!=200 || reply.body().length()>350000)throw new IllegalStateException();
            JsonObject claim=JsonParser.parseString(reply.body()).getAsJsonObject();
            String id=claim.get("id").getAsString(), lease=claim.get("lease_token").getAsString();
            String signature=claim.get("signature").getAsString(), encoded=claim.get("body").getAsString();
            if(!id.matches("[a-f0-9]{64}") || !lease.matches("[a-f0-9]{64}")
                    || !signature.matches("[a-fA-F0-9]{64}") || encoded.length()>349528)throw new IllegalStateException();
            byte[] body=Base64.getDecoder().decode(encoded);
            if(body.length>262144 || !id.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body))))throw new IllegalStateException();
            if(closed)return false;
            // A timeout may leave processing in flight. Never ACK it later; the
            // durable outbox deduplicates any redelivery after lease expiry.
            var result=processor.process(body,signature).toCompletableFuture().get(30,TimeUnit.SECONDS);
            if(result.status()!=200 || closed) {status="RETRY";return false;}
            JsonObject ack=new JsonObject();ack.addProperty("id",id);ack.addProperty("lease_token",lease);
            if(transport.post("/consumer/ack",ack.toString()).status()!=204)throw new IllegalStateException();
            status="PROCESSED";return true;
        } catch(InterruptedException interrupted) {Thread.currentThread().interrupt();status="STOPPED";return false;}
        catch(Exception failure) {status="RETRY";return false;}
    }
    static void validateOrigin(URI origin) {
        if(origin==null || !"https".equalsIgnoreCase(origin.getScheme()) || origin.getHost()==null
                || origin.getUserInfo()!=null || origin.getRawQuery()!=null || origin.getRawFragment()!=null
                || !(origin.getRawPath().isEmpty() || origin.getRawPath().equals("/")))
            throw new IllegalArgumentException("Inbox origin must be HTTPS without credentials, path, query or fragment");
    }
    public String status() {return status;}
    @Override public void close() {
        closed=true;status="STOPPED";scheduler.shutdownNow();
        if(transport instanceof HttpTransport http)http.client.shutdownNow();
    }
    static final class HttpTransport implements Transport {
        private final URI origin;private final String token;private final HttpClient client;
        HttpTransport(URI origin,String token) {this(origin,token,HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build());}
        // Package-private client injection for trusted local HTTPS fixtures only.
        HttpTransport(URI origin,String token,HttpClient client) {
            validateOrigin(origin);
            if(token==null || !token.matches("[A-Za-z0-9_-]{32,256}"))throw new IllegalArgumentException("Inbox consumer token must be 32-256 URL-safe characters");
            if(client.followRedirects()!=HttpClient.Redirect.NEVER)throw new IllegalArgumentException("Inbox redirects must be disabled");
            this.origin=origin;this.token=token;this.client=client;
        }
        public Reply post(String path,String body) throws Exception {
            if(!Set.of("/consumer/claim","/consumer/ack","/consumer/status").contains(path))throw new IllegalArgumentException();
            var request=HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(10))
                    .header("Authorization","Bearer "+token).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var future=client.sendAsync(request,info->new LimitedBody());
            try {
                var result=future.get(10,TimeUnit.SECONDS);
                return new Reply(result.statusCode(),new String(result.body(),StandardCharsets.UTF_8));
            } finally {if(!future.isDone())future.cancel(true);}
        }
    }
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() {return result;}
        public void onSubscribe(Flow.Subscription value) {subscription=value;value.request(1);}
        public void onNext(List<ByteBuffer> buffers) {
            for(ByteBuffer buffer:buffers) {
                if(buffer.remaining()>350000-bytes.size()) {
                    subscription.cancel();result.completeExceptionally(new IllegalStateException("Inbox response exceeds limit"));return;
                }
                byte[] chunk=new byte[buffer.remaining()];buffer.get(chunk);bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) {result.completeExceptionally(error);}
        public void onComplete() {result.complete(bytes.toByteArray());}
    }
}
