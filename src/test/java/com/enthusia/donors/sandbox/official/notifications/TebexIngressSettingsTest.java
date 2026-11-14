package com.enthusia.donors.sandbox.official.notifications;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TebexIngressSettingsTest {
    @Test void defaultOffAndExclusiveModes() {
        var config=new YamlConfiguration();
        assertFalse(TebexIngressSettings.load(config).enabled());
        config.set("notifications.tebex-webhook.enabled",true);
        config.set("notifications.tebex-webhook.pull-enabled",true);
        assertThrows(IllegalArgumentException.class,()->TebexIngressSettings.load(config));
    }
    @Test void outboundNeedsIndependentCredentialsAndNoListeningPort() {
        var config=new YamlConfiguration();String base="notifications.tebex-webhook.";
        config.set(base+"pull-enabled",true);config.set(base+"secret","synthetic-signing-secret-at-least-32-bytes");
        config.set(base+"consumer-token","synthetic-consumer-secret-at-least-32-bytes");
        config.set(base+"inbox-origin","https://inbox.example");
        var settings=TebexIngressSettings.load(config);
        assertTrue(settings.enabled());assertTrue(settings.pullEnabled());assertFalse(settings.httpEnabled());
        assertEquals(0,settings.port());assertFalse(settings.toString().contains("secret"));
        config.set(base+"consumer-token",config.getString(base+"secret"));
        assertThrows(IllegalArgumentException.class,()->TebexIngressSettings.load(config));
    }
}
