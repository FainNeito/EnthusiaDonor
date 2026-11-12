package com.enthusia.donors.sandbox.official.notifications;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.FileConfiguration;

/** Explicit transport choice; secrets are never included in diagnostics. */
record TebexIngressSettings(boolean httpEnabled,boolean pullEnabled,String secret,int port,long waitSeconds,URI origin,String token) {
    static TebexIngressSettings load(FileConfiguration c) {
        String base="notifications.tebex-webhook.";
        boolean http=c.getBoolean(base+"enabled",false),pull=c.getBoolean(base+"pull-enabled",false);
        if(http && pull)throw new IllegalArgumentException("Choose one Tebex webhook transport: loopback or outbound inbox");
        String secret=c.getString(base+"secret",""),token=c.getString(base+"consumer-token","");
        int port=c.getInt(base+"port",0);long wait=c.getLong(base+"wait-seconds",120);URI origin=null;
        if(http || pull) {
            if(secret.getBytes(StandardCharsets.UTF_8).length<32 || wait<1 || wait>=900)
                throw new IllegalArgumentException("Invalid Tebex webhook secret or wait interval");
            if(http && (port<1024 || port>65535))throw new IllegalArgumentException("Invalid Tebex webhook port");
            if(pull) {
                if(secret.length()>256)throw new IllegalArgumentException("Inbox signing secret exceeds supported length");
                try {origin=URI.create(c.getString(base+"inbox-origin",""));TebexInboxConsumer.validateOrigin(origin);}
                catch(IllegalArgumentException invalid) {throw new IllegalArgumentException("Invalid HTTPS inbox origin");}
                if(!token.matches("[A-Za-z0-9_-]{32,256}") || token.equals(secret))
                    throw new IllegalArgumentException("Use a separate 32-256 character URL-safe inbox consumer token");
            }
        }
        return new TebexIngressSettings(http,pull,secret,port,wait,origin,token);
    }
    boolean enabled() {return httpEnabled || pullEnabled;}
    @Override public String toString() {return "TebexIngressSettings[http="+httpEnabled+",pull="+pullEnabled+"]";}
}
