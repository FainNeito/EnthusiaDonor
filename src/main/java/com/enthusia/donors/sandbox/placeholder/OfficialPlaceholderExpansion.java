package com.enthusia.donors.sandbox.placeholder;

import com.enthusia.donors.cache.DonorCache;
import com.enthusia.donors.cache.PlayerStatCache;
import com.enthusia.donors.config.DonorsConfig;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Loaded only after the optional PlaceholderAPI plugin is enabled. */
public final class OfficialPlaceholderExpansion extends PlaceholderExpansion {
    private final JavaPlugin plugin;
    private final DonorCache official;
    private final Supplier<DonorsConfig> config;
    private final PlayerStatCache stats;
    private final BooleanSupplier showAmounts;

    public OfficialPlaceholderExpansion(JavaPlugin plugin, DonorCache official,
                                        Supplier<DonorsConfig> config, PlayerStatCache stats,
                                        BooleanSupplier showAmounts) {
        this.plugin = plugin;
        this.official = official;
        this.config = config;
        this.stats = stats;
        this.showAmounts = showAmounts;
    }

    @Override public @NotNull String getIdentifier() { return "enthusiadonors"; }
    @Override public @NotNull String getAuthor() { return "Enthusia"; }
    @Override public @NotNull String getVersion() { return plugin.getDescription().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override public String onRequest(OfflinePlayer player, @NotNull String params) {
        DonorsConfig current = config.get();
        var display = new OfficialPlaceholderValues.DisplaySettings(
                current.currencySymbol(), current.showCents(), current.emptyName(), current.emptyAmount(),
                current.emptyRank(), current.topSize(), showAmounts.getAsBoolean());
        return OfficialPlaceholderValues.resolve(player == null ? null : player.getUniqueId(), params,
                official, stats.snapshot(), display);
    }
}
