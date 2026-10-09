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
import java.nio.file.*;
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
    private final Map<UUID,HeadEntry> headCache=new ConcurrentHashMap<>();
    private final Map<UUID,CompletableFuture<URL>> headInFlight=new ConcurrentHashMap<>();
    private final Map<UUID,String> headStatus=new ConcurrentHashMap<>();
    private final Path headCachePath;
    private final ExecutorService io=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(64),
            r -> {Thread t=new Thread(r,"EnthusiaDonors-test-skins");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final AtomicBoolean failNext=new AtomicBoolean();
    private volatile TestSettings settings;
    private record HeadEntry(URL url,long fetchedAt,long retryAfter) { }
    public SkinService(TestSettings settings,Path headCachePath) {
        this.settings=settings;this.headCachePath=headCachePath;loadHeadCache();
    }
    public void settings(TestSettings next){settings=next;cache.clear();}
    public void injectFailure(){failNext.set(true);}
    public String headStatus(UUID uuid) {return headStatus.getOrDefault(uuid,"No texture lookup attempted on this backend.");}
    public CompletableFuture<Optional<UUID>> lookupUuidByName(String name) {
        try {
            return CompletableFuture.supplyAsync(() -> MojangProfiles.uuidByName(name,settings.skinTimeout()),io)
                    .exceptionally(error -> Optional.empty());
        } catch(RejectedExecutionException busy) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }
    /** Cached/online textures first; bounded direct reads avoid Paper's authlib timeout logs. */
    public CompletableFuture<URL> getHeadTexture(Person person) {
        if(!Bukkit.isPrimaryThread()) return CompletableFuture.failedFuture(new IllegalStateException("Capture head textures on the main thread."));
        TestSettings config=settings;
        if(person.synthetic()) {
            headStatus.put(person.uuid(),"Synthetic test identity; neutral head expected.");
            return CompletableFuture.completedFuture(null);
        }
        if(!config.skinNetwork()) {
            headStatus.put(person.uuid(),"Skin lookups disabled in testing.yml.");
            return CompletableFuture.completedFuture(null);
        }
        long now=System.currentTimeMillis();
        HeadEntry cached=headCache.get(person.uuid());
        Player online=Bukkit.getPlayer(person.uuid());
        URL onlineSkin=online==null?null:online.getPlayerProfile().getTextures().getSkin();
        if(onlineSkin!=null) {
            Optional<URL> valid=MojangProfiles.validateTextureUrl(onlineSkin.toString());
            if(valid.isPresent()) {
                if(cached==null || !valid.get().equals(cached.url())) {
                    headCache.put(person.uuid(),new HeadEntry(valid.get(),now,0));
                    try {CompletableFuture.runAsync(this::saveHeadCache,io);} catch(RejectedExecutionException ignored) { }
                }
                headStatus.put(person.uuid(),"Online player profile texture available.");
                return CompletableFuture.completedFuture(valid.get());
            }
        }
        long freshFor=TimeUnit.HOURS.toMillis(24);
        if(cached!=null && (cached.retryAfter()>now || cached.url()!=null && now-cached.fetchedAt()<freshFor)) {
            headStatus.put(person.uuid(),cached.url()==null?"Mojang lookup unavailable; retrying after cooldown.":"Saved texture cache available.");
            return CompletableFuture.completedFuture(cached.url());
        }
        CompletableFuture<URL> pending=headInFlight.get(person.uuid());
        if(pending!=null) return pending;
        CompletableFuture<URL> future;
        headStatus.put(person.uuid(),"Looking up Mojang profile for "+person.name()+".");
        try {
            future=CompletableFuture.supplyAsync(() -> {
                Optional<MojangProfiles.TextureResult> found=MojangProfiles.texture(person.uuid(),person.name(),config.skinTimeout());
                headStatus.put(person.uuid(),found.map(result -> "Texture resolved through "+result.source()+".")
                        .orElse("No texture returned by Mojang; check server HTTPS access and account identity."));
                return found.map(MojangProfiles.TextureResult::url).orElse(null);
            },io).exceptionally(error -> {
                headStatus.put(person.uuid(),"Mojang texture lookup failed; retrying after cooldown.");
                return null;
            });
        } catch(RejectedExecutionException busy) {
            headStatus.put(person.uuid(),"Texture lookup queue busy; using cached texture if available.");
            return CompletableFuture.completedFuture(cached==null?null:cached.url());
        }
        headInFlight.put(person.uuid(),future);
        future.whenComplete((url,error) -> {
            long completedAt=System.currentTimeMillis();
            if(url!=null) {
                headCache.put(person.uuid(),new HeadEntry(url,completedAt,0));
                saveHeadCache();
            } else headCache.put(person.uuid(),new HeadEntry(cached==null?null:cached.url(),
                    cached==null?0:cached.fetchedAt(),completedAt+TimeUnit.MINUTES.toMillis(1)));
            headInFlight.remove(person.uuid(),future);
        });
        return future.thenApply(url -> url!=null?url:cached==null?null:cached.url());
    }
    public CompletableFuture<Skin> get(Person person) {
        if(!Bukkit.isPrimaryThread()) return CompletableFuture.failedFuture(new IllegalStateException("Capture skin profiles on the main thread."));
        if(failNext.getAndSet(false)) return CompletableFuture.completedFuture(fallback("Injected skin failure"));
        Entry entry=cache.get(person.uuid()); long now=System.currentTimeMillis();
        if(entry!=null && entry.expires>now) return CompletableFuture.completedFuture(new Skin(entry.skin.pixels,entry.skin.profile,"Cache"));
        CompletableFuture<Skin> pending=inFlight.get(person.uuid()); if(pending!=null) return pending;
        TestSettings config=settings;
        if(person.synthetic() || !config.skinNetwork()) return CompletableFuture.completedFuture(fallback(person.synthetic()?"Synthetic test identity":"Network disabled"));
        CompletableFuture<Skin> future=getHeadTexture(person)
                .thenApplyAsync(url -> {
                    try {if(url==null) return fallback("Skin not available");
                        int[] pixels=download(url,config.skinTimeout()); return new Skin(pixels,null,"Recipient texture");
                    } catch(Exception ex) {return fallback("Skin download unavailable");}
                },io).exceptionally(ex -> entry==null ? fallback("Lookup timeout / unavailable") : new Skin(entry.skin.pixels,entry.skin.profile,"Stale cache"));
        inFlight.put(person.uuid(),future);
        future.thenAccept(result -> {
            if(cache.size()>=256) cache.clear();
            long ttl="Recipient texture".equals(result.source()) ? TimeUnit.MINUTES.toMillis(config.cacheMinutes()) : 30_000;
            cache.put(person.uuid(),new Entry(result,System.currentTimeMillis()+ttl)); inFlight.remove(person.uuid(),future);
        });
        return future;
    }
    private void loadHeadCache() {
        if(!Files.isRegularFile(headCachePath)) return;
        Properties saved=new Properties();
        try(InputStream input=Files.newInputStream(headCachePath)) {
            saved.load(input);
            for(String key:saved.stringPropertyNames()) {
                try {
                    String[] parts=saved.getProperty(key,"").split("\\|",2);
                    if(parts.length!=2) continue;
                    Optional<URL> url=MojangProfiles.validateTextureUrl(parts[1]);
                    if(url.isPresent()) headCache.put(UUID.fromString(key),new HeadEntry(url.get(),Long.parseLong(parts[0]),0));
                } catch(IllegalArgumentException ignored) { }
            }
        } catch(Exception ignored) {
            // A corrupt cache is disposable; public donor totals are stored separately.
        }
    }
    private synchronized void saveHeadCache() {
        Properties saved=new Properties();
        headCache.forEach((uuid,entry) -> {
            if(entry.url()!=null) saved.setProperty(uuid.toString(),entry.fetchedAt()+"|"+entry.url());
        });
        try {
            Files.createDirectories(headCachePath.getParent());
            Path temporary=headCachePath.resolveSibling(headCachePath.getFileName()+".tmp");
            try(OutputStream output=Files.newOutputStream(temporary)) {saved.store(output,"EnthusiaDonors public head texture cache");}
            try {Files.move(temporary,headCachePath,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException unsupported) {Files.move(temporary,headCachePath,StandardCopyOption.REPLACE_EXISTING);}
        } catch(Exception ignored) {
            // Keep the current in-memory texture even if disk caching is unavailable.
        }
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
    @Override public void close(){io.shutdownNow();inFlight.clear();cache.clear();headInFlight.clear();headCache.clear();}
}
