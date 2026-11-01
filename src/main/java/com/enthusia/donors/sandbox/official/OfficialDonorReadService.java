package com.enthusia.donors.sandbox.official;

import com.enthusia.donors.cache.DonorCache;
import com.enthusia.donors.config.ConfigManager;
import com.enthusia.donors.config.DonorsConfig;
import com.enthusia.donors.model.DonorEntry;
import com.enthusia.donors.model.PaymentRecord;
import com.enthusia.donors.model.RefreshState;
import com.enthusia.donors.storage.DonorRepository;
import com.enthusia.donors.tebex.TebexClient;
import com.enthusia.donors.network.*;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Instant;
import java.time.YearMonth;
import java.time.Clock;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Official Tebex read path for public displays; test purchases never enter this repository. */
public final class OfficialDonorReadService implements AutoCloseable {
    private final JavaPlugin plugin;
    private final ConfigManager config;
    private final DonorRepository repository;
    private final TebexClient tebex;
    private final DonorCache cache = new DonorCache();
    private final SharedDonorSnapshots shared;
    private final ExecutorService io = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "EnthusiaDonors-official-read");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private BukkitTask schedule;
    private volatile boolean closed;
    private volatile com.enthusia.donors.glorious.AwardCoordinator.Grant gloriousGrant;
    private volatile com.enthusia.donors.infrastructure.glorious.GloriousPublisherCycle gloriousCycle;
    private volatile com.enthusia.donors.infrastructure.glorious.GloriousAwardService gloriousService;
    private volatile long gloriousEpoch;
    private final AtomicReference<Set<String>> gloriousExclusions=new AtomicReference<>(Set.of());
    private com.enthusia.donors.sandbox.official.notifications.PaymentAnnouncements announcements;
    public void announcements(com.enthusia.donors.sandbox.official.notifications.PaymentAnnouncements value) {announcements=value;}
    public void enableGlorious(com.enthusia.donors.glorious.AwardCoordinator.Grant grant) {
        if(shared==null || config.network().mode()!=NetworkSettings.Mode.PUBLISHER)
            throw new IllegalStateException("Glorious requires a shared publisher");
        gloriousGrant=java.util.Objects.requireNonNull(grant);
    }

    public OfficialDonorReadService(JavaPlugin plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
        this.repository = new DonorRepository(plugin.getDataFolder().toPath().resolve("testing")
                .resolve("official-tebex").resolve(sourceFingerprint(config.get().tebexApiKey())), plugin.getLogger());
        this.tebex = new TebexClient(plugin.getLogger());
        this.shared=config.network().enabled()?new SharedDonorSnapshots(config.network(),config,cache,plugin.getDataFolder().toPath(),plugin.getLogger()):null;
    }

    public DonorCache cache() { return cache; }
    public boolean gloriousAvailable() {return !closed && gloriousGrant!=null && gloriousService!=null && shared!=null
            && shared.ownsLease() && shared.leaseEpoch()==gloriousEpoch;}
    public String networkStatus() {return shared==null?"standalone":config.network().mode()+" | source "+config.network().sourceId()
            +" | revision "+shared.revision()+" | read "+shared.readState()+" | publisher lease "+shared.ownsLease();}

    public void start() {
        if(config.network().mode()==NetworkSettings.Mode.READER) {shared.start();return;}
        CompletableFuture.runAsync(() -> {
            try {
                repository.init();
                if (shared==null && hasKey(config.get())) {
                    List<DonorEntry> saved = repository.loadTotals();
                    if (!saved.isEmpty()) {
                        Instant lastUpdate = Instant.ofEpochMilli(saved.getFirst().updatedAt());
                        cache.replace(forCurrentMonth(saved, lastUpdate, config.get()), lastUpdate, RefreshState.CACHE_ONLY);
                        plugin.getLogger().info("Loaded " + saved.size() + " cached official Tebex donor totals.");
                    }
                }
            } catch (Exception error) {
                cache.markFailure("Official donor cache could not be loaded");
                plugin.getLogger().warning("Official donor cache could not be loaded; existing files were preserved.");
            }
        }, io).thenCompose(ignored -> shared==null?CompletableFuture.completedFuture(null):shared.start()).thenRun(this::refresh);
        schedule();
    }

    public void schedule() {
        if (schedule != null) schedule.cancel();
        long ticks = Math.max(1, config.get().refreshIntervalMinutes()) * 60L * 20L;
        schedule = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, ticks, ticks);
    }

    public CompletableFuture<Boolean> refresh() {
        DonorsConfig current = config.get();
        if (closed) return CompletableFuture.completedFuture(false);
        if(config.network().mode()==NetworkSettings.Mode.READER)return shared.poll();
        if(shared!=null && !shared.ownsLease())return CompletableFuture.completedFuture(false);
        long publicationEpoch=shared==null?0:shared.leaseEpoch();
        if (!hasKey(current)) {
            cache.markNotConfigured();
            plugin.getLogger().warning("Official Tebex donor displays need tebex.api-key in config.yml; test donors will not be substituted.");
            return CompletableFuture.completedFuture(false);
        }
        if (!inFlight.compareAndSet(false, true)) return CompletableFuture.completedFuture(false);
        cache.markAttempt();
        java.util.function.Supplier<CompletableFuture<RefreshData>> fetch=()->tebex.fetchPayments(current)
                .thenCombine(tebex.resolveExcludedPaymentIdHashes(current),RefreshData::new);
        var fetched=gloriousGrant!=null && shared!=null
                ? com.enthusia.donors.infrastructure.glorious.GloriousActivationSequencer.beforeFetch(io,
                    ()->initializeGlorious(current,publicationEpoch),fetch)
                : fetch.get();
        return fetched
                .thenApplyAsync(data -> {
                    try {
                        int count = repository.upsertPayments(data.payments());
                        Instant reconciledAt = Instant.now();
                        List<DonorEntry> donors = repository.rebuildTotals(current, data.excludedHashes(), reconciledAt);
                        if(shared==null)cache.replace(donors, Instant.now(), RefreshState.OK);
                        else shared.publish(donors,current,publicationEpoch,reconciledAt);
                        if(gloriousGrant!=null && shared!=null)kickGlorious(current,data.excludedHashes(),publicationEpoch);
                        if(announcements!=null && (shared==null || shared.ownsLease() && shared.leaseEpoch()==publicationEpoch))announcements.accept(data.payments(),data.excludedHashes(),current);
                        plugin.getLogger().info("Official Tebex donor refresh complete: " + count + " payments, " + donors.size() + " donors.");
                        return true;
                    } catch (Exception error) {
                        throw new IllegalStateException("Official donor cache update failed", error);
                    }
                }, io)
                .exceptionally(error -> {
                    if(shared==null)cache.markFailure("Official Tebex refresh failed");else cache.markNetworkFailure();
                    plugin.getLogger().warning("Official Tebex refresh failed; keeping the last valid official totals. "
                            + safe(error.getMessage(), current.tebexApiKey()));
                    return false;
                })
                .whenComplete((ignored, error) -> inFlight.set(false));
    }

    private static boolean hasKey(DonorsConfig config) {
        return config.tebexApiKey() != null && !config.tebexApiKey().isBlank();
    }

    /** Runs on the official IO worker; database and strict Tebex operations never block the server thread. */
    private void kickGlorious(DonorsConfig current,Set<String> exclusions,long epoch) {
        if(closed || gloriousGrant==null || !shared.ownsLease() || shared.leaseEpoch()!=epoch)return;
        gloriousExclusions.set(Set.copyOf(exclusions));
        initializeGlorious(current,epoch);
        if(gloriousCycle==null || gloriousEpoch!=epoch)return;
        gloriousCycle.tick().whenComplete((result,error)->{
            if(error!=null)plugin.getLogger().warning("Glorious review held: official scan or award processing failed.");
            else if(result==com.enthusia.donors.infrastructure.glorious.GloriousPublisherCycle.Result.HELD)
                plugin.getLogger().warning("Glorious award held for review or incomplete data.");
        });
    }
    /** Called on the IO worker before Tebex access so the database establishes the activation time. */
    private void initializeGlorious(DonorsConfig current,long epoch) {
        if(closed || gloriousGrant==null || shared==null || !shared.ownsLease() || shared.leaseEpoch()!=epoch)return;
        try {
            if(gloriousCycle==null || gloriousEpoch!=epoch) {
                if(gloriousCycle!=null)gloriousCycle.close();
                var service=shared.startGlorious(epoch,current.timezone(),player -> {
                    if(closed || !shared.ownsLease() || shared.leaseEpoch()!=epoch)
                        return com.enthusia.donors.glorious.AwardCoordinator.GrantResult.HELD;
                    return gloriousGrant.apply(player);
                });
                gloriousService=service;
                gloriousEpoch=epoch;
                gloriousCycle=new com.enthusia.donors.infrastructure.glorious.GloriousPublisherCycle(
                        service.boundary(),()->!closed && shared.ownsLease() && shared.leaseEpoch()==epoch,
                        ()->tebex.fetchVerifiedAwardPayments(current),
                        (month,now,scan)->service.evaluate(month,now,scan,current,gloriousExclusions.get(),
                                new BigDecimal(config.glorious().minimumAmount())).result(),io,Clock.systemUTC());
            }
        } catch(Exception failure) {
            plugin.getLogger().warning("Glorious initialization held: verify shared schema, lease and LuckPerms.");
        }
    }

    public CompletableFuture<Set<String>> gloriousPending(YearMonth month) {
        var service=gloriousService;var current=config.get();long epoch=gloriousEpoch;
        if(service==null || closed || shared==null || !shared.ownsLease() || shared.leaseEpoch()!=epoch)
            return CompletableFuture.failedFuture(new IllegalStateException("Glorious publisher is not ready"));
        return tebex.fetchVerifiedAwardPayments(current).thenCombine(tebex.resolveExcludedPaymentIdHashes(current),
                (scan,exclusions)->new GloriousReviewData(scan,exclusions)).thenApplyAsync(data->{
            try {if(closed || !shared.ownsLease() || shared.leaseEpoch()!=epoch)throw new IllegalStateException("Publisher lease lost");
                return service.pending(month,Instant.now(),data.scan(),current,data.exclusions());}
            catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}
        },io);
    }
    public CompletableFuture<Void> gloriousReview(YearMonth month,String hash,java.util.UUID payer,java.util.UUID reviewer,String evidence) {
        var service=gloriousService;var current=config.get();long epoch=gloriousEpoch;
        if(service==null || closed || shared==null || !shared.ownsLease() || shared.leaseEpoch()!=epoch)
            return CompletableFuture.failedFuture(new IllegalStateException("Glorious publisher is not ready"));
        return tebex.fetchVerifiedAwardPayments(current).thenCombine(tebex.resolveExcludedPaymentIdHashes(current),
                (scan,exclusions)->new GloriousReviewData(scan,exclusions)).thenApplyAsync(data->{
            try {if(closed || !shared.ownsLease() || shared.leaseEpoch()!=epoch)throw new IllegalStateException("Publisher lease lost");
                service.review(month,Instant.now(),data.scan(),current,data.exclusions(),hash,payer,reviewer,evidence);return null;}
            catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}
        },io);
    }
    public CompletableFuture<Void> gloriousConfirm(YearMonth month,java.util.UUID winner,java.util.UUID reviewer,String evidence) {
        var service=gloriousService;long epoch=gloriousEpoch;
        if(service==null || closed || shared==null || !shared.ownsLease() || shared.leaseEpoch()!=epoch)
            return CompletableFuture.failedFuture(new IllegalStateException("Glorious publisher is not ready"));
        return CompletableFuture.runAsync(()->{
            try {if(closed || !shared.ownsLease() || shared.leaseEpoch()!=epoch)throw new IllegalStateException("Publisher lease lost");
                service.confirmUncertain(month,winner,reviewer,evidence);}
            catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}
        },io);
    }
    public CompletableFuture<java.util.Optional<com.enthusia.donors.glorious.AwardDecision>> gloriousStatus(YearMonth month) {
        var service=gloriousService;long epoch=gloriousEpoch;
        if(service==null || closed || shared==null || !shared.ownsLease() || shared.leaseEpoch()!=epoch)
            return CompletableFuture.failedFuture(new IllegalStateException("Glorious publisher is not ready"));
        return CompletableFuture.supplyAsync(()->{
            try {if(closed || !shared.ownsLease() || shared.leaseEpoch()!=epoch)throw new IllegalStateException("Publisher lease lost");return service.status(month);}
            catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}
        },io);
    }
    private record GloriousReviewData(com.enthusia.donors.tebex.StableAwardScan scan,Set<String> exclusions) { }

    static List<DonorEntry> forCurrentMonth(List<DonorEntry> saved, Instant updatedAt, DonorsConfig config) {
        YearMonth savedMonth = YearMonth.from(updatedAt.atZone(config.timezone()));
        if (savedMonth.equals(YearMonth.now(config.timezone()))) return saved;
        return saved.stream().map(entry -> new DonorEntry(entry.uuid(), entry.name(), entry.alltimeTotal(),
                java.math.BigDecimal.ZERO, entry.alltimeRank(), 0, entry.updatedAt())).toList();
    }

    private static String sourceFingerprint(String key) {
        if (key == null || key.isBlank()) return "unconfigured";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception error) {
            throw new IllegalStateException("Could not isolate the official Tebex cache", error);
        }
    }

    private static String safe(String value, String key) {
        String text = value == null ? "Unknown error" : value;
        return key == null || key.isBlank() ? text : text.replace(key, "[redacted]");
    }

    @Override public void close() {
        closed = true;
        if(gloriousCycle!=null)gloriousCycle.close();
        if (schedule != null) schedule.cancel();
        if(shared!=null)shared.close();
        io.shutdownNow();
    }

    private record RefreshData(List<PaymentRecord> payments, Set<String> excludedHashes) { }
}
