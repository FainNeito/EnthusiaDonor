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
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private com.enthusia.donors.sandbox.official.notifications.PaymentAnnouncements announcements;
    public void announcements(com.enthusia.donors.sandbox.official.notifications.PaymentAnnouncements value) {announcements=value;}

    public OfficialDonorReadService(JavaPlugin plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
        this.repository = new DonorRepository(plugin.getDataFolder().toPath().resolve("testing")
                .resolve("official-tebex").resolve(sourceFingerprint(config.get().tebexApiKey())), plugin.getLogger());
        this.tebex = new TebexClient(plugin.getLogger());
        this.shared=config.network().enabled()?new SharedDonorSnapshots(config.network(),config,cache,plugin.getDataFolder().toPath(),plugin.getLogger()):null;
    }

    public DonorCache cache() { return cache; }
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
        return tebex.fetchPayments(current)
                .thenCombine(tebex.resolveExcludedPaymentIdHashes(current), RefreshData::new)
                .thenApplyAsync(data -> {
                    try {
                        int count = repository.upsertPayments(data.payments());
                        Instant reconciledAt = Instant.now();
                        List<DonorEntry> donors = repository.rebuildTotals(current, data.excludedHashes(), reconciledAt);
                        if(shared==null)cache.replace(donors, Instant.now(), RefreshState.OK);
                        else shared.publish(donors,current,publicationEpoch,reconciledAt);
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
        if (schedule != null) schedule.cancel();
        if(shared!=null)shared.close();
        io.shutdownNow();
    }

    private record RefreshData(List<PaymentRecord> payments, Set<String> excludedHashes) { }
}
