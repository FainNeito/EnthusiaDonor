package com.enthusia.donors.network;

import com.enthusia.donors.cache.DonorCache;
import com.enthusia.donors.config.*;
import com.enthusia.donors.model.DonorEntry;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/** Shared public totals only; raw payments and sandbox purchases never enter this channel. */
public final class SharedDonorSnapshots implements AutoCloseable {
    private final NetworkSettings settings;private final ConfigManager config;
    private final JdbcProjectionStore store;private final SharedSnapshotConsumer consumer;
    private final Path cacheFile;private final Logger logger;
    private final String bootToken=UUID.randomUUID().toString();
    private final AtomicBoolean ownsLease=new AtomicBoolean();
    private volatile long leaseEpoch;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t=new Thread(r,"EnthusiaDonors-shared-snapshots");t.setDaemon(true);return t;
    });
    private volatile boolean closed;
    private volatile boolean initialized;
    public SharedDonorSnapshots(NetworkSettings settings,ConfigManager config,DonorCache cache,Path dataFolder,Logger logger) {
        this.settings=settings;this.config=config;this.logger=logger;
        this.consumer=new SharedSnapshotConsumer(settings.sourceId(),cache);
        this.store=new JdbcProjectionStore(() -> {
            Properties properties=new Properties();properties.setProperty("user",settings.username());properties.setProperty("password",settings.password());
            properties.setProperty("connectTimeout","5000");properties.setProperty("socketTimeout","5000");
            return DriverManager.getConnection(settings.jdbcUrl(),properties);
        },settings.sourceId(),JdbcProjectionStore.Dialect.MARIADB);
        try {
            String key=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((settings.sourceId()+"\n"+settings.jdbcUrl()).getBytes(StandardCharsets.UTF_8)));
            cacheFile=dataFolder.resolve("testing").resolve("network").resolve(key).resolve("snapshot.json");
        } catch(Exception error) {throw new IllegalStateException("Cannot isolate shared snapshot cache",error);}
    }
    public CompletableFuture<Void> start() {
        return CompletableFuture.runAsync(() -> {
            try {
                if(Files.isRegularFile(cacheFile) && Files.size(cacheFile)<=8_000_000)
                    apply(SnapshotRead.found(NetworkSnapshot.parse(Files.readString(cacheFile))),false);
            } catch(Exception error) {logger.warning("Local shared snapshot cache could not be loaded; preserved for inspection.");}
            if(settings.mode()==NetworkSettings.Mode.PUBLISHER) {
                try {store.initPublisher();initialized=true;renewLease();}
                catch(Exception error) {ownsLease.set(false);logger.warning("Shared publisher initialization failed; shared displays retain their cache.");}
                worker.scheduleWithFixedDelay(this::renewLease,settings.leaseSeconds()/3,settings.leaseSeconds()/3,TimeUnit.SECONDS);
            }
            pollNow();
            worker.scheduleWithFixedDelay(this::pollNow,settings.pollSeconds(),settings.pollSeconds(),TimeUnit.SECONDS);
        },worker);
    }
    private void renewLease() {
        if(closed)return;
        try {
            if(!initialized) {store.initPublisher();initialized=true;}
            boolean acquired=store.acquireLease(bootToken,settings.leaseSeconds()*1000L);
            leaseEpoch=acquired?store.leaseEpoch(bootToken):0;ownsLease.set(leaseEpoch>0);
        } catch(Exception error) {leaseEpoch=0;ownsLease.set(false);}
    }
    public boolean ownsLease() {return ownsLease.get();}
    public long leaseEpoch() {return leaseEpoch;}
    public long revision() {return consumer.revision();}
    public SnapshotRead.Status readState() {return consumer.lastRead();}
    public CompletableFuture<Boolean> poll() {return CompletableFuture.supplyAsync(this::pollNow,worker);}
    private boolean pollNow() {
        if(closed)return false;
        return apply(store.read(),true);
    }
    private synchronized boolean apply(SnapshotRead read,boolean persist) {
        boolean result=consumer.accept(read,Instant.now(),settings.maxAgeSeconds());
        if(result) {
            NetworkSnapshot accepted=consumer.accepted();
            NetworkSnapshot.Display d=accepted.display();
            config.adoptSharedDisplay(ZoneId.of(accepted.timezone()),d.currencySymbol(),d.showCents(),d.topSize(),d.emptyName(),d.emptyAmount(),d.emptyRank());
            if(persist)save(accepted);
        }
        return result;
    }
    /** Invoked on official-read IO executor after a complete Tebex reconciliation. */
    public synchronized void publish(List<DonorEntry> donors,DonorsConfig current,long expectedLeaseEpoch,Instant reconciledAt) throws Exception {
        if(closed || !ownsLease.get())throw new IllegalStateException("This backend does not hold the shared publisher lease");
        Instant now=reconciledAt;
        NetworkSnapshot draft=new NetworkSnapshot(1,settings.sourceId(),0,now.toEpochMilli(),
                YearMonth.from(now.atZone(current.timezone())).toString(),current.timezone().getId(),
                new NetworkSnapshot.Display(current.currencySymbol(),current.showCents(),current.topSize(),current.emptyName(),current.emptyAmount(),current.emptyRank()),donors);
        NetworkSnapshot committed=store.publish(bootToken,expectedLeaseEpoch,draft);
        apply(SnapshotRead.found(committed),true);
    }
    private synchronized void save(NetworkSnapshot snapshot) {
        try {
            Files.createDirectories(cacheFile.getParent());Path temporary=cacheFile.resolveSibling("snapshot.json.tmp");
            Files.writeString(temporary,snapshot.json(),StandardCharsets.UTF_8);
            try {Files.move(temporary,cacheFile,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException unsupported) {Files.move(temporary,cacheFile,StandardCopyOption.REPLACE_EXISTING);}
        } catch(Exception error) {logger.warning("Shared snapshot is in memory but could not be saved to local disk.");}
    }
    @Override public void close() {closed=true;ownsLease.set(false);worker.shutdownNow();}
}
