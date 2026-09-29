package com.enthusia.donors.sandbox.skin;

import com.enthusia.donors.sandbox.TestSettings;
import com.enthusia.donors.sandbox.domain.Domain.Person;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.profile.PlayerProfile;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;

/** Main-thread profile capture, async lookup/download/decode, bounded memory and network work. */
@SuppressWarnings("deprecation")
public final class SkinService implements AutoCloseable {
    public record Skin(int[] pixels,PlayerProfile profile,String source) { }
    private record Entry(Skin skin,long expires) { }
    private final Map<UUID,Entry> cache=new ConcurrentHashMap<>();
    private final Map<UUID,CompletableFuture<Skin>> inFlight=new ConcurrentHashMap<>();
    private final ExecutorService io=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(64),
            r -> {Thread t=new Thread(r,"EnthusiaDonors-test-skins");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final AtomicBoolean failNext=new AtomicBoolean();
    private volatile TestSettings settings;
    public SkinService(TestSettings settings) {this.settings=settings;}
    public void settings(TestSettings next){settings=next;cache.clear();}
    public void injectFailure(){failNext.set(true);}
    public CompletableFuture<Skin> get(Person person) {
        if(!Bukkit.isPrimaryThread()) return CompletableFuture.failedFuture(new IllegalStateException("Capture skin profiles on the main thread."));
        if(failNext.getAndSet(false)) return CompletableFuture.completedFuture(fallback("Injected skin failure"));
        Entry entry=cache.get(person.uuid()); long now=System.currentTimeMillis();
        if(entry!=null && entry.expires>now) return CompletableFuture.completedFuture(new Skin(entry.skin.pixels,entry.skin.profile,"Cache"));
        CompletableFuture<Skin> pending=inFlight.get(person.uuid()); if(pending!=null) return pending;
        TestSettings config=settings;
        if(person.synthetic() || !config.skinNetwork()) return CompletableFuture.completedFuture(fallback(person.synthetic()?"Synthetic test identity":"Network disabled"));
        Player online=Bukkit.getPlayer(person.uuid());
        PlayerProfile profile=online==null ? Bukkit.createPlayerProfile(person.uuid(),person.name()) : online.getPlayerProfile();
        CompletableFuture<? extends PlayerProfile> profileFuture=profile.getTextures().getSkin()!=null
                ? CompletableFuture.completedFuture(profile) : profile.update();
        CompletableFuture<Skin> future=profileFuture.orTimeout(config.skinTimeout(),TimeUnit.SECONDS)
                .thenApplyAsync(p -> {
                    try {URL url=p.getTextures().getSkin(); if(url==null) return fallback("Skin not available");
                        int[] pixels=download(url,config.skinTimeout()); return new Skin(pixels,p,"Recipient texture");
                    } catch(Exception ex) {return fallback("Skin download unavailable");}
                },io).exceptionally(ex -> entry==null ? fallback("Lookup timeout / unavailable") : new Skin(entry.skin.pixels,entry.skin.profile,"Stale cache"));
        inFlight.put(person.uuid(),future);
        future.thenAccept(result -> {
            if(cache.size()>=256) cache.clear();
            long ttl=result.profile==null ? 30_000 : TimeUnit.MINUTES.toMillis(config.cacheMinutes());
            cache.put(person.uuid(),new Entry(result,System.currentTimeMillis()+ttl)); inFlight.remove(person.uuid(),future);
        });
        return future;
    }
    private int[] download(URL texture,int timeout) throws Exception {
        URI original=texture.toURI();
        if(!"textures.minecraft.net".equalsIgnoreCase(original.getHost()) || !original.getPath().matches("/texture/[A-Fa-f0-9]{32,128}")
                || original.getUserInfo()!=null || original.getQuery()!=null || original.getPort()!=-1 || original.getFragment()!=null)
            throw new IOException("Unsupported texture host or path");
        URI uri=URI.create("https://textures.minecraft.net"+original.getPath());
        HttpURLConnection connection=(HttpURLConnection)uri.toURL().openConnection();
        connection.setConnectTimeout(timeout*1000); connection.setReadTimeout(timeout*1000);
        connection.setInstanceFollowRedirects(false); connection.setRequestProperty("User-Agent","EnthusiaDonors-Test/1");
        byte[] bytes;
        try {
            if(connection.getResponseCode()!=200) throw new IOException("Texture HTTP failure");
            try(InputStream input=connection.getInputStream()) {
                bytes=input.readNBytes(1_048_577); if(bytes.length>1_048_576) throw new IOException("Skin is too large");
            }
        } finally {connection.disconnect();}
        try(ImageInputStream imageInput=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers=ImageIO.getImageReaders(imageInput); if(!readers.hasNext()) throw new IOException("Unknown image format");
            ImageReader reader=readers.next();
            try {reader.setInput(imageInput); int w=reader.getWidth(0),h=reader.getHeight(0);
                if(w<64 || w>1024 || w%64!=0 || (h!=w && h!=w/2)) throw new IOException("Invalid skin dimensions");
                BufferedImage image=reader.read(0); return FacePixels.extract(image);
            } finally {reader.dispose();}
        }
    }
    private Skin fallback(String source){return new Skin(FacePixels.fallback(),null,source);}
    @Override public void close(){io.shutdownNow();inFlight.clear();cache.clear();}
}
