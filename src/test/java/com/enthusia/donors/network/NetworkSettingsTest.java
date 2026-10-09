package com.enthusia.donors.network;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NetworkSettingsTest {
    @Test void absentNetworkConfigPreservesStandalone() {
        var settings=NetworkSettings.load(new YamlConfiguration());
        assertEquals(NetworkSettings.Mode.STANDALONE,settings.mode());
        assertFalse(settings.enabled());
    }
    @Test void networkModesRequireExplicitMariaDbAndSafeSource() {
        assertThrows(IllegalArgumentException.class,() -> new NetworkSettings(NetworkSettings.Mode.READER,"test","","","",15,60,1200));
        assertThrows(IllegalArgumentException.class,() -> new NetworkSettings(NetworkSettings.Mode.READER,"Test","jdbc:mariadb://host/db","","",15,60,1200));
        assertThrows(IllegalArgumentException.class,() -> new NetworkSettings(NetworkSettings.Mode.PUBLISHER,"test","jdbc:sqlite:db","","",15,60,1200));
        var settings=new NetworkSettings(NetworkSettings.Mode.READER,"test","jdbc:mariadb://host/db","user","secret",15,60,1200);
        assertTrue(settings.enabled());
        assertFalse(settings.toString().contains("secret"));
        assertFalse(settings.toString().contains("host/db"));
    }
}
