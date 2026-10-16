package com.enthusia.donors;

import com.enthusia.donors.sandbox.TestRuntime;
import com.enthusia.donors.infrastructure.LegacyRuntime;
import com.enthusia.donors.infrastructure.RuntimeMode;
import org.bukkit.plugin.java.JavaPlugin;

/** Original runtime by default; donor-network adapters require explicit operator opt-in. */
public final class EnthusiaDonorsPlugin extends JavaPlugin {
    private AutoCloseable runtime;
    private com.enthusia.donors.infrastructure.heads.HeadEmojiLifecycle headEmojis;
    @Override public void onEnable(){
        try {
            saveDefaultConfig();reloadConfig();
            if(RuntimeMode.load(getConfig())==RuntimeMode.LEGACY) {
                var legacy=new LegacyRuntime(this);runtime=legacy;legacy.start();
            } else runtime=new TestRuntime(this);
            headEmojis=new com.enthusia.donors.infrastructure.heads.HeadEmojiLifecycle(this,()->{
                if(runtime instanceof LegacyRuntime legacy)return legacy.donorCache();
                if(runtime instanceof TestRuntime network)return network.officialDonors().cache();
                return null;
            });
            headEmojis.start();
        } catch(Exception ex) {
            getLogger().severe("Donor runtime initialization failed; inspect configuration and server dependencies. Secrets were not logged.");
            getServer().getPluginManager().disablePlugin(this);
        }
    }
    @Override public void onDisable(){
        if(headEmojis!=null)headEmojis.close();
        if(runtime!=null)try {runtime.close();}catch(Exception stopped){getLogger().warning("Donor runtime shutdown failed; inspect retained data before restarting.");}
    }
}
