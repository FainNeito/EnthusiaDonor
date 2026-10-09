package com.enthusia.donors.sandbox;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import java.net.URI;
import java.nio.file.*;
import java.time.ZoneId;
import java.util.*;

public record TestSettings(ZoneId zone,String storeUrl,boolean skinNetwork,int skinTimeout,int cacheMinutes,
                           boolean showAmounts,boolean showGiftIdentities,long avidCents,long devoteeCents,int termDays,
                           boolean discordEnabled,String discordUrl,String discordUsername,
                           boolean serverBroadcasts,boolean networkBroadcasts,Map<String,List<String>> templates,
                           String broadcastHeader,String broadcastMarker,String topDivider,String bottomDivider,
                           String storeCta,String storeDisplayText,Map<String,String> rankFormats) {
    public static final Map<String,String> DEFAULT_RANK_FORMATS=Map.of(
            "avid","<#00C4FF><bold>[<#17CCFF>A<#2ED3FF>v<#44DBFF>i<#5BE2FF>d<#72EAFF>]</bold><reset>",
            "devotee","<#0007FF><bold>[<#070CFF>D<#0E12FF>e<#1417FF>v<#1B1DFF>o<#2222FF>t<#2927FF>e<#2F2DFF>e<#3632FF>]</bold>",
            "glorious","<b><gradient:#FF110A:#C70000>[Glorious]</gradient></b>");
    public static final String DEFAULT_DIVIDER="<gray>────────────────────────────────────────</gray>";
    public static TestSettings load(JavaPlugin plugin) throws Exception {
        Path path=plugin.getDataFolder().toPath().resolve("testing.yml");
        Files.createDirectories(path.getParent());
        if(!Files.exists(path)) plugin.saveResource("testing.yml",false);
        YamlConfiguration y=YamlConfiguration.loadConfiguration(path.toFile());
        String store=y.getString("store-url","").strip();
        if(!store.isEmpty()) requireHttps(store,false);
        String hook=y.getString("discord.test-webhook-url","").strip();
        if(!hook.isEmpty()) requireHttps(hook,true);
        Map<String,List<String>> templates=new HashMap<>();
        for(String type:List.of("purchase","subscription","gift","renewal","glorious")) {
            List<String> lines=y.getStringList("broadcast."+type);
            if(lines.size()>8 || lines.stream().anyMatch(l -> l.length()>300 || l.contains("\n")))
                throw new IllegalArgumentException("Broadcast templates need at most eight short lines.");
            templates.put(type,List.copyOf(lines));
        }
        Map<String,String> rankFormats=new HashMap<>();
        for(String rank:List.of("avid","devotee","glorious")) {
            String format=y.getString("broadcast.rank-formats."+rank,DEFAULT_RANK_FORMATS.get(rank));
            if(format.length()>500 || format.contains("\n") || format.contains("\r"))
                throw new IllegalArgumentException("Rank formats must be one short line.");
            rankFormats.put(rank,format);
        }
        String header=shortLine(y.getString("broadcast.header","$$ Donation Broadcast"),"broadcast.header",200);
        String marker=shortLine(y.getString("broadcast.marker","[TEST]"),"broadcast.marker",80);
        if(marker.isBlank()) throw new IllegalArgumentException("broadcast.marker must identify this as a test build.");
        String top=shortLine(y.getString("broadcast.top-divider",DEFAULT_DIVIDER),"broadcast.top-divider",300);
        String bottom=shortLine(y.getString("broadcast.bottom-divider",DEFAULT_DIVIDER),"broadcast.bottom-divider",300);
        String cta=shortLine(y.getString("broadcast.store-cta","Donate to support the server"),"broadcast.store-cta",200);
        String storeDisplay=shortLine(y.getString("broadcast.store-display-text",""),"broadcast.store-display-text",200);
        return new TestSettings(ZoneId.of(y.getString("timezone","America/Chicago")),store,y.getBoolean("skins.network-enabled",true),
                clamp(y.getInt("skins.timeout-seconds",4),1,10),clamp(y.getInt("skins.cache-minutes",60),1,1440),
                y.getBoolean("privacy.show-amounts",true),y.getBoolean("privacy.show-gift-identities",true),
                money(y.getString("test-prices.avid","20.00")),money(y.getString("test-prices.devotee","5.00")),
                clamp(y.getInt("test-subscription-days",30),1,366),y.getBoolean("discord.enabled",false),hook,
                y.getString("discord.username","Enthusia Purchases [TEST]"),y.getBoolean("broadcast.allow-server-audience",false),
                y.getBoolean("broadcast.allow-network-audience",false),Map.copyOf(templates),
                header,marker,top,bottom,cta,storeDisplay,Map.copyOf(rankFormats));
    }
    private static String shortLine(String value,String key,int max) {
        if(value==null || value.length()>max || value.contains("\n") || value.contains("\r"))
            throw new IllegalArgumentException(key+" must be a single short line.");
        return value;
    }
    public static long money(String value) {
        try {
            java.math.BigDecimal n=new java.math.BigDecimal(value);
            long cents=n.setScale(2,java.math.RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
            if(cents<0 || cents>100_000_000L) throw new IllegalArgumentException("Test amount must be 0 to 1,000,000.");
            return cents;
        } catch(ArithmeticException | NumberFormatException ex) {throw new IllegalArgumentException("Use a non-negative amount with at most two decimal places.");}
    }
    public static URI requireHttps(String value,boolean webhook) {
        try {
            URI u=URI.create(value);
            if(!"https".equalsIgnoreCase(u.getScheme()) || u.getHost()==null || u.getUserInfo()!=null || u.getFragment()!=null || u.getPort()!=-1)
                throw new IllegalArgumentException("Use a normal HTTPS URL without user information or a port.");
            if(webhook && (!(u.getHost().equals("discord.com") || u.getHost().equals("canary.discord.com") || u.getHost().equals("ptb.discord.com"))
                    || !u.getPath().matches("/api(?:/v[0-9]+)?/webhooks/[0-9]+/[A-Za-z0-9_-]+") || u.getQuery()!=null))
                throw new IllegalArgumentException("Use the unmodified URL of a dedicated Discord test-channel webhook.");
            return u;
        } catch(IllegalArgumentException ex) {throw new IllegalArgumentException(webhook ? "Invalid Discord test webhook URL (value redacted)." : "Invalid store URL.");}
    }
    private static int clamp(int n,int low,int high) {return Math.max(low,Math.min(n,high));}
}
