package com.enthusia.donors.sandbox.official.notifications;

import com.enthusia.donors.config.*;
import com.enthusia.donors.model.PaymentRecord;
import com.enthusia.donors.relay.*;
import com.enthusia.donors.sandbox.domain.Domain.Person;
import com.enthusia.donors.sandbox.skin.SkinService;
import com.enthusia.donors.storage.DonorRepository;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Official payment observations only. No sandbox purchase or preview can call this adapter. */
public final class PaymentAnnouncements implements AutoCloseable {
    private final NotificationSettings settings;private final NotificationStore store;private final RelayStore relay;
    private final SkinService skins;private final JavaPlugin plugin;private final String source,backend,key;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"EnthusiaDonors-payments");t.setDaemon(true);return t;});
    private final AtomicBoolean accepting=new AtomicBoolean();private final DiscordWebhook discord=DiscordWebhook.http();
    private final NotificationDispatcher dispatcher;
    private final boolean webhookEnabled; private final long webhookWaitSeconds;
    private TebexHttpReceiver receiver;
    private record EligibilitySnapshot(DonorsConfig config,Set<String> exclusions,Instant refreshedAt) { }
    private volatile EligibilitySnapshot eligibilitySnapshot;
    private volatile boolean closed;private boolean ready;private volatile String status="STARTING",ingestionStatus="WAITING_FOR_FIRST_SYNC";private long lastWarning;
    public static PaymentAnnouncements create(JavaPlugin plugin,ConfigManager config,SkinService skins) {
        NotificationSettings settings=NotificationSettings.load(plugin.getConfig(),config.network());
        return settings.enabled()?new PaymentAnnouncements(plugin,config,skins,settings):null;
    }
    private PaymentAnnouncements(JavaPlugin plugin,ConfigManager config,SkinService skins,NotificationSettings settings) {
        this.plugin=plugin;this.skins=skins;this.settings=settings;source=config.network().sourceId();
        if(config.get().tebexApiKey().isBlank())throw new IllegalArgumentException("Real payment notifications need the publisher Tebex API key.");
        backend=plugin.getConfig().getString("relay.backend-id","");key=plugin.getConfig().getString("relay.signing-key","");
        Properties credentials=new Properties();credentials.setProperty("user",config.network().username());credentials.setProperty("password",config.network().password());credentials.setProperty("connectTimeout","5000");credentials.setProperty("socketTimeout","5000");
        NotificationStore.Connections connections=()->DriverManager.getConnection(config.network().jdbcUrl(),credentials);
        // Stable store namespace survives Tebex key rotation and publisher failover.
        store=new NotificationStore(connections,NotificationStore.Dialect.MARIADB,source);
        // Producer account may have different privileges from the snapshot publisher.
        Properties relayCredentials=new Properties();relayCredentials.setProperty("user",plugin.getConfig().getString("relay.username",""));relayCredentials.setProperty("password",plugin.getConfig().getString("relay.password",""));relayCredentials.setProperty("connectTimeout","5000");relayCredentials.setProperty("socketTimeout","5000");
        relay=new RelayStore(()->DriverManager.getConnection(config.network().jdbcUrl(),relayCredentials),RelayStore.Dialect.MARIADB);
        dispatcher=new NotificationDispatcher(store,relay::publish,payload->discord.send(settings.webhook(),payload));
        webhookEnabled=plugin.getConfig().getBoolean("notifications.tebex-webhook.enabled",false);
        webhookWaitSeconds=plugin.getConfig().getLong("notifications.tebex-webhook.wait-seconds",120);
        if(webhookEnabled) {
            String secret=plugin.getConfig().getString("notifications.tebex-webhook.secret","");
            int port=plugin.getConfig().getInt("notifications.tebex-webhook.port",0);
            if(secret==null || secret.getBytes(StandardCharsets.UTF_8).length<32 || port<1024 || port>65535 || webhookWaitSeconds<1 || webhookWaitSeconds>=900)
                throw new IllegalArgumentException("Invalid Tebex webhook secret, port or wait interval");
            var client=new com.enthusia.donors.tebex.TebexClient(plugin.getLogger());
            try {
                receiver=TebexWebhookAssembly.loopback(new java.net.InetSocketAddress("127.0.0.1",port),secret,store,client,config.get(),
                        payment->{
                            var snapshot=eligibilitySnapshot;
                            if(snapshot==null || Instant.now().isAfter(snapshot.refreshedAt().plusSeconds(Math.max(120L,snapshot.config().refreshIntervalMinutes()*120L))))
                                throw new IllegalStateException("Official eligibility snapshot unavailable or stale");
                            return eligible(payment,snapshot.config(),snapshot.exclusions(),Instant.now());
                        },(payment,event)->payload(payment,Instant.now(),event.kind()==TebexWebhookEvidence.Kind.RENEWAL));
                receiver.start();
            } catch(java.io.IOException failure) {throw new IllegalStateException("Unable to bind Tebex webhook receiver",failure);}
        }
        worker.scheduleWithFixedDelay(this::flush,0,2,TimeUnit.SECONDS);
    }
    private void initialize() throws SQLException {
        if(ready)return;
        if(settings.initializeSchema())store.init();
        else store.register();
        if(settings.chat() && plugin.getConfig().getBoolean("relay.initialize-schema",false))relay.init();
        // Read even when tables were provisioned externally, so errors never masquerade as ready.
        store.status();ready=true;
    }
    public void accept(List<PaymentRecord> payments,Set<String> exclusions,DonorsConfig config) {
        if(closed || !accepting.compareAndSet(false,true))return;
        try {worker.execute(()->{
            try {
                initialize();Instant now=Instant.now();
                Map<String,NotificationStore.Payload> prepared=new HashMap<>();
                for(PaymentRecord payment:store.candidates(payments,now,p->eligible(p,config,exclusions,now))) {
                    if(closed)return;
                    prepared.computeIfAbsent(payment.paymentIdHash(),ignored->payload(payment,Instant.now()));
                }
                int queued=webhookEnabled
                        ?store.observeDeferred(payments,now,p->eligible(p,config,exclusions,now),p->Objects.requireNonNull(prepared.get(p.paymentIdHash()),"Concurrent source initialization; retry on the next poll"))
                        :store.observe(payments,now,p->eligible(p,config,exclusions,now),p->Objects.requireNonNull(prepared.get(p.paymentIdHash()),"Concurrent source initialization; retry on the next poll"));
                eligibilitySnapshot=new EligibilitySnapshot(config,Set.copyOf(exclusions),now);
                ingestionStatus="OBSERVED | queued "+queued;
            } catch(Exception failure) {ingestionStatus="OBSERVATION_ERROR";warn("OBSERVATION_ERROR");}
            finally {accepting.set(false);}
        });} catch(RejectedExecutionException stopped) {accepting.set(false);}
    }
    public static boolean eligible(PaymentRecord p,DonorsConfig config,Set<String> exclusions,Instant now) {
        return p.playerName()!=null && p.playerName().matches("[A-Za-z0-9_. -]{1,32}") && p.playerUuid()!=null && !p.createdAt().isAfter(now)
                && p.amount().signum()>0 && !p.manualPayment() && !p.refundedOrChargeback()
                && Set.of("complete","completed","successful","success","paid").contains(p.status().trim().toLowerCase(Locale.ROOT))
                && DonorRepository.isCountable(p.amount(),p.status(),false,false,new HashSet<>(p.packageIds()),p.paymentIdHash(),exclusions,config);
    }
    private NotificationStore.Payload payload(PaymentRecord payment,Instant now) {return payload(payment,now,false);}
    private NotificationStore.Payload payload(PaymentRecord payment,Instant now,boolean renewal) {
        String chat=null;
        if(settings.chat()) {
            int[] pixels=new int[64];Arrays.fill(pixels,0x777777);
            try {
                CompletableFuture<int[]> face=new CompletableFuture<>();
                // SkinService captures online Bukkit profiles before doing its async lookup.
                // Keep that capture on the server thread; only this worker awaits the result.
                org.bukkit.Bukkit.getScheduler().runTask(plugin,()->{
                    if(closed) {face.completeExceptionally(new IllegalStateException("Stopping"));return;}
                    try {skins.get(new Person(payment.playerUuid(),payment.playerName(),false)).whenComplete((skin,error)->{
                        if(error!=null)face.completeExceptionally(error);else face.complete(skin.pixels());
                    });} catch(Exception error) {face.completeExceptionally(error);}
                });
                pixels=face.get(12,TimeUnit.SECONDS);
            }
            catch(InterruptedException interrupted) {Thread.currentThread().interrupt();throw new CompletionException(interrupted);}
            catch(Exception unavailable) { /* Neutral face still delivers the confirmed payment. */ }
            var lines=PaymentMessages.chat(payment,pixels,settings,renewal).stream().map(GsonComponentSerializer.gson()::serialize).toList();
            chat=RelayEvent.sign(source,"payment-"+hash(source+"\n"+payment.paymentIdHash()),backend,now,900,lines,key).json();
        }
        return new NotificationStore.Payload(chat,settings.discord()?PaymentMessages.discord(payment,settings,renewal):null);
    }
    private void flush() {
        if(closed)return;
        try {
            initialize();
            // Previously held jobs must remain recoverable if the experimental receiver is disabled.
            store.releaseDeferred(Instant.now(),webhookEnabled?webhookWaitSeconds:120);
            if(dispatcher.pump(Instant.now(),settings.chat(),settings.discord())>0)warn("OUTBOX_OR_DELIVERY_ERROR");
            else status="READY | "+store.status();
        } catch(Exception unavailable) {warn("OUTBOX_OR_DELIVERY_ERROR");}
    }
    private void warn(String state) {
        status=state;long now=System.currentTimeMillis();if(now-lastWarning>60_000) {lastWarning=now;plugin.getLogger().warning("Real donation announcements: "+state+". Persisted jobs/claims retained; check publisher database and notification status. No unsaved fallback or automatic uncertain resend.");}
    }
    public String status() {return ingestionStatus+" | "+status;}
    private static String hash(String value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception error) {throw new IllegalStateException("Notification identity unavailable",error);}
    }
    @Override public void close() {closed=true;if(receiver!=null)receiver.close();worker.shutdownNow();}
}
