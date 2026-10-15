package com.enthusia.donors.infrastructure.entitlement;

import com.enthusia.donors.entitlement.Reconciliation;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.*;
import java.util.Map;

/** Optional independent adapter. Sandbox commands have no reference to it. */
public final class ReconciliationLifecycle implements AutoCloseable {
    private final JavaPlugin plugin;
    private final ReconciliationSettings settings;
    private Reconciliation engine;
    private ReconciliationFiles files;
    private TebexEntitlements source;
    private FileChannel channel;
    private FileLock lock;
    private BukkitTask task;
    private volatile boolean closed;
    private boolean running;

    public ReconciliationLifecycle(JavaPlugin plugin) { this.plugin = plugin; settings = ReconciliationSettings.load(plugin.getConfig()); }
    public void start() throws Exception {
        if (!settings.enabled()) return;
        if (plugin.getServer().getPluginManager().getPlugin("LuckPerms") == null)
            throw new IllegalStateException("Entitlement reconciliation requires LuckPerms");
        Path directory = plugin.getDataFolder().toPath().resolve("entitlement-reconciliation");
        Files.createDirectories(directory);
        channel = FileChannel.open(directory.resolve("owner.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        lock = channel.tryLock();
        if (lock == null) throw new IllegalStateException("Another local entitlement reconciliation owner exists");
        files = new ReconciliationFiles(directory, settings.enforce());
        source = new TebexEntitlements(TebexEntitlements.http(settings.key(), settings.timeout()), settings.packages(), settings.serverId(), settings.maxHistory());
        engine = new Reconciliation(new LuckPermsRanks(settings, () -> closed), source::check, files,
                settings.enforce(), settings.confirmation(), settings.interval().multipliedBy(3));
        task = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, this::poll, 20L, settings.interval().toSeconds() * 20L);
        plugin.getLogger().info("Devotee reconciliation enabled in " + (settings.enforce() ? "ENFORCE" : "AUDIT")
                + " mode; interval=" + settings.interval().toMinutes() + "m, confirmation=" + settings.confirmation().toMinutes() + "m. Run only one enforcement owner per shared LuckPerms database.");
    }
    private synchronized boolean begin() {
        if (closed || running) return false;
        running = true; return true;
    }
    private void poll() {
        if (!begin()) return;
        try {
            try { source.validate(); }
            catch (Exception unavailable) {
                // Only discard valid pending observations. Preserve corrupted files for review.
                files.load(); files.save(Map.of());
                plugin.getLogger().warning("Devotee reconciliation verification unavailable; retaining ranks and resetting confirmation. Retry at the next scheduled check.");
                return;
            }
            if (!closed) plugin.getLogger().info("Devotee reconciliation: " + engine.run(Instant.now()));
        } catch (Exception failure) {
            engine.stop();
            plugin.getLogger().severe("Devotee reconciliation halted after a state/audit failure. Preserve local evidence and inspect any UNCERTAIN save before reactivation. Secrets and response bodies were not logged.");
        } finally {
            synchronized (this) { running = false; if (closed) release(); }
        }
    }
    public synchronized void close() {
        closed = true;
        if (engine != null) engine.stop();
        if (task != null) task.cancel();
        if (!running) release();
    }
    private void release() {
        try { if (lock != null && lock.isValid()) lock.release(); } catch (Exception ignored) { /* Closed on shutdown. */ }
        try { if (channel != null) channel.close(); } catch (Exception ignored) { /* Closed on shutdown. */ }
    }
}
