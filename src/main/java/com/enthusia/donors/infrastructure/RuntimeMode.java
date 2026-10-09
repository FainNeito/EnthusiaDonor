package com.enthusia.donors.infrastructure;
import org.bukkit.configuration.file.FileConfiguration;
import java.util.Locale;
/** Explicit platform startup routing; preserves canonical main's original runtime. */
public enum RuntimeMode {
    LEGACY,DONOR_NETWORK;
    public static RuntimeMode load(FileConfiguration config) {
        RuntimeMode mode=switch(config.getString("runtime.mode","legacy").trim().toLowerCase(Locale.ROOT)) {
            case "legacy" -> LEGACY;
            case "donor-network" -> DONOR_NETWORK;
            default -> throw new IllegalArgumentException("runtime.mode must be legacy or donor-network");
        };
        if(mode==LEGACY && (config.getBoolean("notifications.enabled",false)
                || !"standalone".equalsIgnoreCase(config.getString("network.mode","standalone"))
                || config.getBoolean("relay.enabled",false)))
            throw new IllegalArgumentException("Shared donor data and notifications require runtime.mode: donor-network");
        return mode;
    }
}
