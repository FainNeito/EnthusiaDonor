package com.enthusia.donors.infrastructure.entitlement;

import org.bukkit.configuration.ConfigurationSection;
import java.time.Duration;
import java.util.*;

public record ReconciliationSettings(boolean enabled, boolean enforce, Set<Long> packages, long serverId,
        Duration interval, Duration confirmation, Set<String> protectedGroups, Set<UUID> protectedUsers,
        int maxUsers, int maxHistory, Duration timeout, String key) {
    public static ReconciliationSettings load(ConfigurationSection config) {
        var section = config.getConfigurationSection("entitlements");
        if (section == null || !section.getBoolean("enabled", false))
            return new ReconciliationSettings(false, false, Set.of(), 0, Duration.ofMinutes(15), Duration.ofMinutes(60), Set.of(), Set.of(), 100, 100, Duration.ofSeconds(10), "");
        String mode = section.getString("mode", "audit");
        if (!Set.of("audit", "enforce").contains(mode)) throw new IllegalArgumentException("entitlements.mode must be audit or enforce");
        var packages = new HashSet<Long>();
        for (Object item : section.getList("devotee-package-ids", List.of())) {
            long id = new java.math.BigDecimal(item.toString()).longValueExact();
            if (id <= 0) throw new IllegalArgumentException("Positive Devotee package IDs required");
            packages.add(id);
        }
        long serverId = section.getLong("delivery-server-id", 0);
        String key = config.getString("tebex.api-key", "").trim();
        if (packages.isEmpty() || serverId <= 0 || key.isBlank()) throw new IllegalArgumentException("Explicit Tebex packages, delivery server and key required");
        int interval = bounded(section, "check-interval-minutes", 15, 1, 1440);
        int confirmation = bounded(section, "confirmation-minutes", 60, 15, 10080);
        var groups = new HashSet<>(Set.of("founder", "avid-founder", "devotee-founder", "media", "glorious"));
        section.getStringList("protected-groups").forEach(name -> groups.add(name.toLowerCase(Locale.ROOT)));
        var users = new HashSet<UUID>(); section.getStringList("protected-uuids").forEach(id -> users.add(UUID.fromString(id)));
        return new ReconciliationSettings(true, mode.equals("enforce"), Set.copyOf(packages), serverId,
                Duration.ofMinutes(interval), Duration.ofMinutes(confirmation), Set.copyOf(groups), Set.copyOf(users),
                bounded(section, "max-users", 100, 1, 10000), bounded(section, "max-history-per-user", 100, 1, 1000),
                Duration.ofSeconds(bounded(section, "timeout-seconds", 10, 1, 30)), key);
    }
    private static int bounded(ConfigurationSection section, String key, int fallback, int min, int max) {
        Object raw = section.get(key, fallback);
        int value = new java.math.BigDecimal(raw.toString()).intValueExact();
        if (value < min || value > max) throw new IllegalArgumentException("Invalid entitlements." + key);
        return value;
    }
    @Override public String toString() { return "ReconciliationSettings[enabled=" + enabled + ", enforce=" + enforce + ", secrets=redacted]"; }
}
