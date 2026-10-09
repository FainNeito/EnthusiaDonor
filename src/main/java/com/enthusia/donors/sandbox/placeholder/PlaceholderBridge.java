package com.enthusia.donors.sandbox.placeholder;

import com.enthusia.donors.cache.PlayerStatCache;
import com.enthusia.donors.cache.DonorCache;
import com.enthusia.donors.config.DonorsConfig;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** This class is loaded only after PlaceholderAPI has been found at runtime. */
public final class PlaceholderBridge {
    private PlaceholderBridge() { }

    public static Runnable register(JavaPlugin plugin, DonorCache official, Supplier<DonorsConfig> config,
                                    PlayerStatCache stats, BooleanSupplier showAmounts) {
        OfficialPlaceholderExpansion expansion = new OfficialPlaceholderExpansion(plugin, official, config, stats, showAmounts);
        return expansion.register() ? expansion::unregister : null;
    }
}
