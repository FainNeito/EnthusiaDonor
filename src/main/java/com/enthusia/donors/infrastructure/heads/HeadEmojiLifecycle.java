package com.enthusia.donors.infrastructure.heads;

import com.enthusia.donors.cache.DonorCache;
import com.enthusia.donors.heads.HeadEmojiQueue;
import com.enthusia.donors.model.RefreshState;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;

/** One optional source writer. No pack reload/build/upload/dispatch mechanism. */
public final class HeadEmojiLifecycle implements AutoCloseable {
    private final JavaPlugin plugin;private final Supplier<DonorCache> cache;
    private BukkitTask task;private FileChannel channel;private FileLock lock;private volatile boolean closed;private boolean running;
    public HeadEmojiLifecycle(JavaPlugin plugin,Supplier<DonorCache> cache){this.plugin=plugin;this.cache=cache;}
    public void start()throws Exception {
        var config=plugin.getConfig();if(!config.getBoolean("head-emojis.enabled",false))return;
        var nexo=plugin.getServer().getPluginManager().getPlugin("Nexo");
        if(nexo==null||!nexo.isEnabled())throw new IllegalStateException("Head emoji queue needs Nexo");
        String server=config.getString("head-emojis.permission-server","test");
        if(server==null||!server.matches("[A-Za-z0-9_-]{1,64}")||server.equalsIgnoreCase("global"))
            throw new IllegalArgumentException("Head permission server must match the local LuckPerms server context");
        var permissions=ContextualHeadPermissions.create(plugin,server,()->!closed);
        int batch=config.getInt("head-emojis.batch-size",5),timeout=config.getInt("head-emojis.timeout-seconds",3);
        if(batch<1||batch>50||timeout<1||timeout>10)throw new IllegalArgumentException("Head queue bounds invalid");
        Map<UUID,UUID> verified=new HashMap<>();var section=config.getConfigurationSection("head-emojis.verified-players");
        if(section!=null)for(String key:section.getKeys(false))verified.put(UUID.fromString(key),UUID.fromString(section.getString(key,"")));
        // Prevent two store IDs from claiming the same Minecraft owner through explicit bindings.
        if(new HashSet<>(verified.values()).size()!=verified.size())throw new IllegalArgumentException("Duplicate head owner binding");
        Path state=plugin.getDataFolder().toPath().resolve("head-emojis");Files.createDirectories(state);
        channel=FileChannel.open(nexo.getDataFolder().toPath().resolve(".donor-head-writer.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        lock=channel.tryLock();if(lock==null)throw new IllegalStateException("Another donor head source writer exists");
        var textures=new HeadTextureSource(verified,timeout);
        var glyphs=new NexoGlyphFiles(nexo.getDataFolder().toPath(),state.resolve("pending-publication.yml"),textures,()->!closed);
        var queue=new HeadEmojiQueue(textures,glyphs,permissions,()->!closed);
        task=plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin,()->poll(queue,batch),20L,1200L);
        plugin.getLogger().info("Donor head queue enabled. New source glyphs require a planned operator pack update; automatic pack dispatch is disabled.");
    }
    private synchronized boolean begin(){if(closed||running)return false;running=true;return true;}
    private void poll(HeadEmojiQueue queue,int batch){
        if(!begin())return;
        try {
            var source=cache.get();
            if(source==null||source.state()!=RefreshState.OK)return;
            var snapshot=source.snapshot();
            if(snapshot.updatedAt()==null||snapshot.updatedAt().isBefore(java.time.Instant.now().minusSeconds(3600)))return;
            var result=queue.accept(snapshot.alltime(),true,batch);
            if(result.completed()+result.held()+result.failed()>0)plugin.getLogger().info("Donor head queue: "+result+". Staged additions await an operator pack update.");
        }catch(Exception failure){plugin.getLogger().warning("Donor head queue deferred after a source/permission error; existing glyphs retained.");}
        finally{synchronized(this){running=false;if(closed)release();}}
    }
    @Override public synchronized void close(){
        closed=true;if(task!=null)task.cancel();
        if(!running)release();
    }
    private void release(){
        try{if(lock!=null&&lock.isValid())lock.release();}catch(Exception ignored){}
        try{if(channel!=null)channel.close();}catch(Exception ignored){}
    }
}
