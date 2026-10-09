package com.enthusia.donors.network;
import com.enthusia.donors.infrastructure.RuntimeMode;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RuntimeModeTest {
    @Test void missingModePreservesLegacyAndNetworkRequiresOptIn() {
        var config=new YamlConfiguration();
        assertEquals(RuntimeMode.LEGACY,RuntimeMode.load(config));
        config.set("runtime.mode","donor-network");
        assertEquals(RuntimeMode.DONOR_NETWORK,RuntimeMode.load(config));
        config.set("runtime.mode","typo");
        assertThrows(IllegalArgumentException.class,()->RuntimeMode.load(config));
    }
    @Test void legacyRejectsUnconsumedNetworkAndNotificationSettings() {
        var config=new YamlConfiguration();config.set("notifications.enabled",true);
        assertThrows(IllegalArgumentException.class,()->RuntimeMode.load(config));
        config.set("notifications.enabled",false);config.set("network.mode","publisher");
        assertThrows(IllegalArgumentException.class,()->RuntimeMode.load(config));
        config.set("runtime.mode","donor-network");
        assertEquals(RuntimeMode.DONOR_NETWORK,RuntimeMode.load(config));
    }
}
