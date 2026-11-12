package com.enthusia.donors.sandbox.official.notifications;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.time.Duration;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Explicitly started loopback-only transport for a reverse proxy. Not registered at plugin startup. */
public final class TebexHttpReceiver implements AutoCloseable {
    private static final int MAX_BODY=262144;
    private final TebexWebhookEndpoint endpoint;
    private final HttpServer server;
    private final ExecutorService executor;
    private final ScheduledThreadPoolExecutor deadlines;
    private final long readDeadlineMillis;
    public TebexHttpReceiver(InetSocketAddress address, TebexWebhookEndpoint endpoint) throws IOException {
        this(address,endpoint,Duration.ofSeconds(5));
    }
    TebexHttpReceiver(InetSocketAddress address, TebexWebhookEndpoint endpoint, Duration readDeadline) throws IOException {
        if(readDeadline.isNegative() || readDeadline.isZero() || readDeadline.toMillis()<1)throw new IllegalArgumentException("Positive read deadline required");
        readDeadlineMillis=readDeadline.toMillis();
        InetAddress host=address.getAddress();
        if(host==null || !host.isLoopbackAddress())throw new IllegalArgumentException("Receiver must bind to a loopback address");
        this.endpoint=endpoint;
        server=HttpServer.create(address,16);
        executor=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"EnthusiaDonors-webhook");t.setDaemon(true);return t;});
        deadlines=new ScheduledThreadPoolExecutor(1,r->{Thread t=new Thread(r,"EnthusiaDonors-webhook-deadline");t.setDaemon(true);return t;});
        deadlines.setRemoveOnCancelPolicy(true);
        server.setExecutor(executor);
        server.createContext("/tebex",this::handle);
    }
    public void start() {server.start();}
    public int port() {return server.getAddress().getPort();}
    private void handle(HttpExchange exchange) throws IOException {
        try(exchange) {
            if(!"/tebex".equals(exchange.getRequestURI().getPath()) || exchange.getRequestURI().getRawQuery()!=null) {reply(exchange,404,"");return;}
            if(!"POST".equals(exchange.getRequestMethod())) {reply(exchange,405,"");return;}
            // Reject invalid authentication metadata before consuming an attacker-controlled body.
            var signatures=exchange.getRequestHeaders().get("X-Signature");
            if(signatures==null || signatures.size()!=1 || !signatures.get(0).matches("[0-9a-fA-F]{64}")) {
                reply(exchange,403,"");return;
            }
            String length=exchange.getRequestHeaders().getFirst("Content-Length");
            if(length!=null) {
                try {
                    long declared=Long.parseLong(length);
                    if(declared<0) {reply(exchange,400,"");return;}
                    if(declared>MAX_BODY) {reply(exchange,413,"");return;}
                } catch(NumberFormatException invalid) {reply(exchange,400,"");return;}
            }
            // Before response headers are sent, closing the exchange aborts the socket read.
            var deadline=deadlines.schedule(exchange::close,readDeadlineMillis,TimeUnit.MILLISECONDS);
            byte[] body;
            try {body=exchange.getRequestBody().readNBytes(MAX_BODY+1);}
            finally {deadline.cancel(false);}
            if(body.length>MAX_BODY) {reply(exchange,413,"");return;}
            try {
                var result=endpoint.handle(body,exchange.getRequestHeaders().getFirst("X-Signature"),Instant.now())
                        .toCompletableFuture().get(15,TimeUnit.SECONDS);
                reply(exchange,result.status(),result.body());
            } catch(Exception failure) {reply(exchange,503,"");}
        }
    }
    private static void reply(HttpExchange exchange,int status,String text) throws IOException {
        byte[] bytes=text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
        exchange.sendResponseHeaders(status,bytes.length);
        try(var output=exchange.getResponseBody()) {output.write(bytes);}
    }
    @Override public void close() {server.stop(0);executor.shutdownNow();deadlines.shutdownNow();}
}
