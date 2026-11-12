package com.enthusia.donors.sandbox.official.notifications;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Explicitly started loopback-only transport for a reverse proxy. Not registered at plugin startup. */
public final class TebexHttpReceiver implements AutoCloseable {
    private static final int MAX_BODY=262144;
    private final TebexWebhookEndpoint endpoint;
    private final HttpServer server;
    private final ExecutorService executor;
    public TebexHttpReceiver(InetSocketAddress address, TebexWebhookEndpoint endpoint) throws IOException {
        InetAddress host=address.getAddress();
        if(host==null || !host.isLoopbackAddress())throw new IllegalArgumentException("Receiver must bind to a loopback address");
        this.endpoint=endpoint;
        server=HttpServer.create(address,16);
        executor=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"EnthusiaDonors-webhook");t.setDaemon(true);return t;});
        server.setExecutor(executor);
        server.createContext("/tebex",this::handle);
    }
    public void start() {server.start();}
    public int port() {return server.getAddress().getPort();}
    private void handle(HttpExchange exchange) throws IOException {
        try(exchange) {
            if(!"/tebex".equals(exchange.getRequestURI().getPath()) || exchange.getRequestURI().getRawQuery()!=null) {reply(exchange,404,"");return;}
            if(!"POST".equals(exchange.getRequestMethod())) {reply(exchange,405,"");return;}
            byte[] body=exchange.getRequestBody().readNBytes(MAX_BODY+1);
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
    @Override public void close() {server.stop(0);executor.shutdownNow();}
}
