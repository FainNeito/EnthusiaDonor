package com.enthusia.donors.sandbox.relay;

import com.enthusia.donors.relay.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Explicit test broadcasts only. Source cache/payment processing does not enqueue notices. */
public final class BackendRelay implements AutoCloseable {
    private final String source,backend,key;private final int ttl;
    private final LocalOutbox outbox;private final RelayStore remote;private final JavaPlugin plugin;private final boolean createSchema;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r -> {Thread t=new Thread(r,"EnthusiaDonors-relay-outbox");t.setDaemon(true);return t;});
    private boolean localReady,remoteReady;private volatile boolean closed;
    private volatile String status="STARTING";private long lastWarning;
    public static BackendRelay create(JavaPlugin plugin) throws Exception {
        return plugin.getConfig().getBoolean("relay.enabled",false)?new BackendRelay(plugin):null;
    }
    private BackendRelay(JavaPlugin plugin) throws Exception {
        this.plugin=plugin;FileConfiguration c=plugin.getConfig();
        source=c.getString("relay.source-id","enthusia-donors-test");backend=c.getString("relay.backend-id","");key=c.getString("relay.signing-key","");ttl=c.getInt("relay.expiry-seconds",300);
        String url=c.getString("relay.jdbc-url","");int interval=c.getInt("relay.poll-seconds",2);createSchema=c.getBoolean("relay.initialize-schema",false);
        if(!RelayEvent.safeId(source) || !RelayEvent.safeId(backend) || key.getBytes(StandardCharsets.UTF_8).length<32 || !url.startsWith("jdbc:mariadb://") || ttl<30 || ttl>900 || interval<1 || interval>60)
            throw new IllegalArgumentException("Invalid relay configuration; supply explicit test database, identities and a 32-byte signing key");
        String fingerprint=hash(source+"\n"+backend+"\n"+url);
        outbox=new LocalOutbox(plugin.getDataFolder().toPath().resolve("testing/relay").resolve(fingerprint).resolve("outbox.db"));
        Properties credentials=new Properties();credentials.setProperty("user",c.getString("relay.username",""));credentials.setProperty("password",c.getString("relay.password",""));credentials.setProperty("connectTimeout","5000");credentials.setProperty("socketTimeout","5000");
        remote=new RelayStore(() -> DriverManager.getConnection(url,credentials),RelayStore.Dialect.MARIADB);
        worker.scheduleWithFixedDelay(this::flush,0,interval,TimeUnit.SECONDS);
    }
    private void initLocal() throws Exception {if(!localReady) {outbox.init();localReady=true;}}
    public CompletableFuture<String> enqueue(String noticeId,List<Component> lines) {
        if(closed)return CompletableFuture.failedFuture(new IllegalStateException("Relay is stopped"));
        List<String> json=lines.stream().map(GsonComponentSerializer.gson()::serialize).toList();
        return CompletableFuture.supplyAsync(() -> {
            try {
                initLocal();String id=hash(source+"\n"+noticeId);
                RelayEvent event=RelayEvent.sign(source,id,backend,Instant.now(),ttl,json,key);outbox.put(event);
                status="QUEUED | event "+id;return id;
            } catch(Exception failure) {throw new CompletionException(new IllegalStateException("Relay local enqueue failed; no fallback broadcast was sent"));}
        },worker);
    }
    private void flush() {
        if(closed)return;
        try {
            initLocal();if(createSchema && !remoteReady) {remote.init();remoteReady=true;}
            int pending=outbox.pending().size();outbox.flush(remote);
            status=pending==0?"IDLE | no pending events":"OK | published/expired batch "+pending;
        } catch(Exception failure) {
            status="OUTBOX_OR_DATABASE_ERROR";
            long now=System.currentTimeMillis();if(now-lastWarning>60_000) {lastWarning=now;plugin.getLogger().warning("Relay outbox publication failed; saved test events retained until expiry. No MessageRaw fallback.");}
        }
    }
    public String status() {return status;}
    private static String hash(String value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception failure) {throw new IllegalStateException("Relay identity hashing failed",failure);}
    }
    @Override public void close() {closed=true;worker.shutdownNow();}
}
