package com.enthusia.donors.infrastructure.heads;

import com.enthusia.donors.heads.HeadEmojiQueue;
import com.google.gson.JsonParser;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import java.util.function.BooleanSupplier;

/** Additive source staging. Never writes generated pack.zip or calls Nexo. */
public final class NexoGlyphFiles implements HeadEmojiQueue.Glyphs {
    @FunctionalInterface public interface Pictures {byte[] load(UUID owner) throws Exception;}
    private final Path nexo,pending;private final Pictures pictures;private final BooleanSupplier active;
    public NexoGlyphFiles(Path nexo,Path pending,Pictures pictures){this(nexo,pending,pictures,()->true);}
    public NexoGlyphFiles(Path nexo,Path pending,Pictures pictures,BooleanSupplier active){this.nexo=nexo.toAbsolutePath().normalize();this.pending=pending;this.pictures=pictures;this.active=active;}
    private void checkActive()throws IOException{if(!active.getAsBoolean())throw new IOException("Head writer closing");}
    private Path registry(){return nexo.resolve("glyphs/donor_heads.yml");}
    private YamlConfiguration read(Path path)throws Exception {
        var yaml=new YamlConfiguration();if(Files.exists(path))yaml.load(path.toFile());return yaml;
    }
    @Override public boolean exists(HeadEmojiQueue.Owner owner)throws Exception {
        var yaml=read(registry());
        if(!yaml.contains(owner.id()))return false;
        if(!owner.permission().equals(yaml.getString(owner.id()+".permission")))throw new IOException("Owner permission mismatch");
        String texture=yaml.getString(owner.id()+".texture","");
        if(!texture.equals("enthusia_donor_heads:heads/"+owner.uuid().toString().replace("-","")))throw new IOException("Unexpected owned texture");
        return Files.isRegularFile(nexo.resolve("pack/assets/enthusia_donor_heads/textures/heads/"+owner.uuid().toString().replace("-","")+".png"));
    }
    @Override public synchronized void stage(HeadEmojiQueue.Owner owner)throws Exception {
        checkActive();
        if(exists(owner))return;
        Path target=registry();var yaml=read(target);
        if(yaml.contains(owner.id()))throw new IOException("Incomplete existing glyph requires operator repair");
        String before=Files.exists(target)?Files.readString(target):null;
        Set<Integer> occupied=new HashSet<>();Set<String> aliases=new HashSet<>();
        Path glyphs=nexo.resolve("glyphs");
        if(Files.isDirectory(glyphs))try(var paths=Files.walk(glyphs)){
            for(Path path:paths.filter(p->p.toString().endsWith(".yml")||p.toString().endsWith(".yaml")).toList()){
                var config=read(path);
                for(String key:config.getKeys(false)){
                    String chars=config.getString(key+".char","");chars.codePoints().forEach(occupied::add);
                    config.getStringList(key+".placeholders").forEach(a->aliases.add(a.toLowerCase(Locale.ROOT)));
                }
            }
        }
        Path pack=nexo.resolve("pack/pack.zip");
        if(Files.exists(pack))try(var zip=new ZipFile(pack.toFile())){
            for(var entry:Collections.list(zip.entries()))if(entry.getName().contains("/font/")&&entry.getName().endsWith(".json")){
                try(var input=zip.getInputStream(entry)){
                    var root=JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
                    if(root.has("providers"))for(var p:root.getAsJsonArray("providers"))if(p.getAsJsonObject().has("chars"))
                        for(var chars:p.getAsJsonObject().getAsJsonArray("chars"))chars.getAsString().codePoints().forEach(occupied::add);
                }
            }
        }
        if(aliases.contains(owner.alias()))throw new IOException("Username shorthand already reserved");
        int code=0xE800;while(code<=0xF8FF&&occupied.contains(code))code++;
        if(code>0xF8FF)throw new IOException("No unoccupied glyph character");
        byte[] png=pictures.load(owner.uuid());
        checkActive();
        if(png.length<8||png[0]!=(byte)0x89||png[1]!='P'||png[2]!='N'||png[3]!='G')throw new IOException("Invalid head PNG");
        String stem=owner.uuid().toString().replace("-","");String key=owner.id();
        yaml.set(key+".texture","enthusia_donor_heads:heads/"+stem);
        yaml.set(key+".font","minecraft:default");yaml.set(key+".ascent",7);yaml.set(key+".height",8);
        yaml.set(key+".default_shadow","#00000000");yaml.set(key+".char",String.valueOf((char)code));
        yaml.set(key+".permission",owner.permission());yaml.set(key+".tabcomplete",true);yaml.set(key+".is_emoji",true);
        yaml.set(key+".placeholders",List.of(owner.alias()));
        // Record pending intent before source writes, so an interrupted addition stays visible.
        var queue=read(pending);queue.set(stem+".name",owner.name());queue.set(stem+".glyph",key);
        queue.set(stem+".publication","operator pack update required");checkActive();atomic(pending,queue.saveToString().getBytes(StandardCharsets.UTF_8));
        Path image=nexo.resolve("pack/assets/enthusia_donor_heads/textures/heads/"+stem+".png");
        if(Files.exists(image)&&!Arrays.equals(Files.readAllBytes(image),png))throw new IOException("Unregistered texture already exists");
        checkActive();atomic(image,png);
        if(!Objects.equals(before,Files.exists(target)?Files.readString(target):null))throw new IOException("Registry changed during generation");
        if(before!=null)atomic(pending.resolveSibling("donor-heads-before-latest-addition.yml"),before.getBytes(StandardCharsets.UTF_8));
        checkActive();atomic(target,yaml.saveToString().getBytes(StandardCharsets.UTF_8));
    }
    static void atomic(Path target,byte[] bytes)throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path tmp=Files.createTempFile(target.toAbsolutePath().getParent(),".head-",".tmp");
        try {Files.write(tmp,bytes);Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        finally {Files.deleteIfExists(tmp);}
    }
}
