package com.enthusia.donors.sandbox.official.notifications;

import com.enthusia.donors.network.NetworkSettings;
import com.enthusia.donors.relay.RelayEvent;
import com.enthusia.donors.sandbox.TestSettings;
import org.bukkit.configuration.file.FileConfiguration;

public record NotificationSettings(boolean enabled,boolean chat,boolean discord,String webhook,String storeUrl,boolean showAmounts,boolean initializeSchema) {
    public static NotificationSettings load(FileConfiguration c,NetworkSettings network) {
        boolean enabled=c.getBoolean("notifications.enabled",false),chat=c.getBoolean("notifications.chat-enabled",true),discord=c.getBoolean("notifications.discord.enabled",true);
        String webhook=c.getString("notifications.discord.webhook-url",""),store=c.getString("notifications.store-url","");
        if(!store.isBlank())TestSettings.requireHttps(store,false);
        if(!webhook.isBlank())TestSettings.requireHttps(webhook,true);
        if(enabled) {
            if(network.mode()!=NetworkSettings.Mode.PUBLISHER)throw new IllegalArgumentException("Real payment notifications require network.mode=publisher; disable on readers.");
            if(!chat && !discord)throw new IllegalArgumentException("Enable at least one real notification channel.");
            if(discord && webhook.isBlank())throw new IllegalArgumentException("Set notifications.discord.webhook-url for smp-chatter before enabling real announcements.");
            if(chat && (!c.getBoolean("relay.enabled",false) || !network.jdbcUrl().equals(c.getString("relay.jdbc-url","")) || !network.sourceId().equals(c.getString("relay.source-id",""))
                    || !RelayEvent.safeId(c.getString("relay.backend-id","")) || c.getString("relay.signing-key","").getBytes(java.nio.charset.StandardCharsets.UTF_8).length<32))
                throw new IllegalArgumentException("Real chat notifications require a relay matching the publisher source/database with a backend ID and signing key.");
        }
        return new NotificationSettings(enabled,chat,discord,webhook,store,c.getBoolean("notifications.show-amounts",false),c.getBoolean("notifications.initialize-schema",false));
    }
    @Override public String toString() {return "NotificationSettings[enabled="+enabled+",chat="+chat+",discord="+discord+"]";}
}
