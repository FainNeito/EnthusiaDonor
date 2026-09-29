package com.enthusia.donors;

import com.enthusia.donors.sandbox.TestRuntime;
import org.bukkit.plugin.java.JavaPlugin;

/** TEST ARTIFACT ONLY. Upstream services remain in source but are not started by this entry point. */
public final class EnthusiaDonorsPlugin extends JavaPlugin {
    private TestRuntime runtime;
    @Override public void onEnable(){
        try{runtime=new TestRuntime(this);}
        catch(Exception ex){getLogger().severe("Test configuration could not be loaded: "+ex.getMessage());getServer().getPluginManager().disablePlugin(this);}
    }
    @Override public void onDisable(){if(runtime!=null)runtime.close();}
}
