package com.enthusia.donors.infrastructure;

import com.enthusia.donors.cache.*;
import com.enthusia.donors.command.DonorCommand;
import com.enthusia.donors.config.ConfigManager;
import com.enthusia.donors.export.*;
import com.enthusia.donors.placeholder.PlaceholderHook;
import com.enthusia.donors.service.*;
import com.enthusia.donors.storage.*;
import com.enthusia.donors.tebex.TebexClient;
import org.bukkit.plugin.java.JavaPlugin;

/** Canonical main's lifecycle, retained alongside the opt-in donor-network adapter. */
public final class LegacyRuntime implements AutoCloseable {
    private final JavaPlugin plugin;
    private ConfigManager configManager;
    private LeaderboardService leaderboardService;
    private PlayerStatService playerStatService;
    private PlaceholderHook placeholderHook;
    public LegacyRuntime(JavaPlugin plugin) {this.plugin=plugin;}
    public void start() {
        configManager=new ConfigManager(plugin);configManager.load();
        var cache=new DonorCache();var playerStatCache=new PlayerStatCache();
        leaderboardService=new LeaderboardService(plugin,configManager,
                new DonorRepository(plugin.getDataFolder().toPath(),plugin.getLogger()),
                new TebexClient(plugin.getLogger()),cache,
                new JsonExportService(plugin.getLogger()),new R2UploadService(plugin.getLogger()));
        leaderboardService.start();
        playerStatService=new PlayerStatService(plugin,new PlayerStatRepository(plugin.getDataFolder().toPath()),playerStatCache);
        playerStatService.start();
        var command=plugin.getCommand("enthusiadonors");
        if(command!=null) {
            var executor=new DonorCommand(configManager,cache,leaderboardService,this::reload);
            command.setExecutor(executor);command.setTabCompleter(executor);
        }
        if(plugin.getServer().getPluginManager().getPlugin("PlaceholderAPI")!=null) {
            placeholderHook=new PlaceholderHook(plugin,configManager,cache,playerStatCache);
            placeholderHook.register();
            plugin.getLogger().info("Registered PlaceholderAPI expansion: enthusiadonors.");
        } else plugin.getLogger().info("PlaceholderAPI not found; placeholders are disabled, plugin remains active.");
    }
    private void reload() {
        configManager.load();leaderboardService.schedule();leaderboardService.refresh(false);
    }
    @Override public void close() {
        if(placeholderHook!=null)placeholderHook.unregister();
        if(leaderboardService!=null)leaderboardService.shutdown();
        if(playerStatService!=null)playerStatService.shutdown();
    }
}
