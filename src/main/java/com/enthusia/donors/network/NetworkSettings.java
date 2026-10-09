package com.enthusia.donors.network;

import org.bukkit.configuration.file.FileConfiguration;
import java.util.Locale;

public record NetworkSettings(Mode mode,String sourceId,String jdbcUrl,String username,String password,
                              int pollSeconds,int leaseSeconds,int maxAgeSeconds) {
    public enum Mode {STANDALONE,PUBLISHER,READER}
    public NetworkSettings {
        if(mode==null || sourceId==null || !sourceId.matches("[a-z0-9_.-]{1,64}"))throw new IllegalArgumentException("Invalid network source");
        if(mode!=Mode.STANDALONE && (jdbcUrl==null || !jdbcUrl.startsWith("jdbc:mariadb://")))
            throw new IllegalArgumentException("Network snapshots require an explicit MariaDB JDBC URL");
        if(pollSeconds<5 || leaseSeconds<30 || maxAgeSeconds<30)throw new IllegalArgumentException("Invalid network snapshot intervals");
    }
    public static NetworkSettings load(FileConfiguration c) {
        Mode mode=Mode.valueOf(c.getString("network.mode","standalone").toUpperCase(Locale.ROOT));
        return new NetworkSettings(mode,c.getString("network.source-id","enthusia-donors-test"),
                c.getString("network.jdbc-url",""),c.getString("network.username",""),c.getString("network.password",""),
                c.getInt("network.poll-seconds",15),c.getInt("network.lease-seconds",60),c.getInt("network.max-snapshot-age-seconds",1200));
    }
    public boolean enabled() {return mode!=Mode.STANDALONE;}
    @Override public String toString() {return "NetworkSettings[mode="+mode+", sourceId="+sourceId+"]";}
}
