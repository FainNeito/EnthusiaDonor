package com.enthusia.donors.infrastructure.heads;

import com.enthusia.donors.heads.HeadEmojiQueue;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import static org.junit.jupiter.api.Assertions.*;

class NexoGlyphFilesTest {
    @TempDir Path dir;
    HeadEmojiQueue.Owner owner=new HeadEmojiQueue.Owner(UUID.fromString("12345678-1234-4234-8234-123456789abc"),"Donor");
    Path registry(){return dir.resolve("glyphs/donor_heads.yml");}
    Path pending(){return dir.resolve("state/pending.yml");}
    byte[] png()throws Exception{var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(8,8,BufferedImage.TYPE_INT_ARGB),"PNG",out);return out.toByteArray();}
    void original(String text)throws Exception{Files.createDirectories(registry().getParent());Files.writeString(registry(),text);}
    @Test void allocationPreservesExistingFontAndNeverRewritesOrPublishesPack()throws Exception{
        original("other:\n  permission: keep.me\n  char: '\ue800'\n");
        Path pack=dir.resolve("pack/pack.zip");Files.createDirectories(pack.getParent());
        try(var zip=new ZipOutputStream(Files.newOutputStream(pack))){zip.putNextEntry(new ZipEntry("assets/other/font/default.json"));zip.write("{\"providers\":[{\"chars\":[\"\ue801\"]}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();}
        byte[] before=Files.readAllBytes(pack);
        var files=new NexoGlyphFiles(dir,pending(),id->png());files.stage(owner);
        var yaml=new YamlConfiguration();yaml.load(registry().toFile());
        assertEquals("keep.me",yaml.getString("other.permission"));assertEquals("\ue802",yaml.getString(owner.id()+".char"));
        assertEquals(List.of(":donor:"),yaml.getStringList(owner.id()+".placeholders"));
        assertEquals("#00000000",yaml.getString(owner.id()+".default_shadow"));assertEquals(owner.permission(),yaml.getString(owner.id()+".permission"));
        assertArrayEquals(before,Files.readAllBytes(pack));assertTrue(Files.readString(pending()).contains("operator pack update required"));
    }
    @Test void restartKeepsSameGlyphAndDoesNotDownloadAgain()throws Exception{
        var files=new NexoGlyphFiles(dir,pending(),id->png());files.stage(owner);byte[] before=Files.readAllBytes(registry());
        var restarted=new NexoGlyphFiles(dir,pending(),id->{throw new IOException("must not fetch existing skin");});
        assertTrue(restarted.exists(owner));restarted.stage(owner);assertArrayEquals(before,Files.readAllBytes(registry()));
    }
    @Test void usernameCollisionFailsWithoutChangingExistingFiles()throws Exception{
        original("somebody:\n  placeholders: [':donor:']\n");byte[] before=Files.readAllBytes(registry());
        assertThrows(IOException.class,()->new NexoGlyphFiles(dir,pending(),id->png()).stage(owner));assertArrayEquals(before,Files.readAllBytes(registry()));
    }
    @Test void networkFailureRetainsRegistryAndDoesNotInsertFallback()throws Exception{
        original("other:\n  char: '\ue800'\n");byte[] before=Files.readAllBytes(registry());
        assertThrows(IOException.class,()->new NexoGlyphFiles(dir,pending(),id->{throw new IOException();}).stage(owner));assertArrayEquals(before,Files.readAllBytes(registry()));
    }
    @Test void corruptRegistryIsNotSilentlyReplaced()throws Exception{
        original("bad: [unclosed");
        assertThrows(Exception.class,()->new NexoGlyphFiles(dir,pending(),id->png()).stage(owner));assertEquals("bad: [unclosed",Files.readString(registry()));
    }
    @Test void closingDuringSkinFetchLeavesSourceUnchanged()throws Exception{
        boolean[] active={true};original("other: {}\n");
        var files=new NexoGlyphFiles(dir,pending(),id->{active[0]=false;return png();},()->active[0]);
        assertThrows(IOException.class,()->files.stage(owner));assertEquals("other: {}\n",Files.readString(registry()));assertFalse(Files.exists(pending()));
    }
    @Test void changedRegistryDuringDownloadIsPreserved()throws Exception{
        original("other: {}\n");var files=new NexoGlyphFiles(dir,pending(),id->{Files.writeString(registry(),"operator_edit: {}\n");return png();});
        assertThrows(IOException.class,()->files.stage(owner));assertEquals("operator_edit: {}\n",Files.readString(registry()));
    }
    @Test void foreignPermissionIsNeverAdopted()throws Exception{
        original(owner.id()+":\n  permission: another.owner\n");assertThrows(IOException.class,()->new NexoGlyphFiles(dir,pending(),id->png()).exists(owner));
    }
}
