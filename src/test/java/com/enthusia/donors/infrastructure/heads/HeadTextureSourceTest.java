package com.enthusia.donors.infrastructure.heads;

import org.junit.jupiter.api.Test;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;
import java.util.*;
import java.math.BigDecimal;
import com.enthusia.donors.model.DonorEntry;
import static org.junit.jupiter.api.Assertions.*;

class HeadTextureSourceTest {
    byte[] encode(BufferedImage image)throws Exception{var out=new ByteArrayOutputStream();ImageIO.write(image,"PNG",out);return out.toByteArray();}
    @Test void legacyOpaqueHatRegionDoesNotHideFace()throws Exception{
        var skin=new BufferedImage(64,32,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<32;y++)for(int x=0;x<64;x++)skin.setRGB(x,y,0xFF000000);
        for(int y=8;y<16;y++)for(int x=8;x<16;x++)skin.setRGB(x,y,0xFF123456);
        var head=ImageIO.read(new ByteArrayInputStream(HeadTextureSource.face(encode(skin))));assertEquals(8,head.getWidth());assertEquals(0xFF123456,head.getRGB(0,0));
    }
    @Test void partialHatIsCompositedOnFace()throws Exception{
        var skin=new BufferedImage(64,64,BufferedImage.TYPE_INT_ARGB);skin.setRGB(8,8,0xFF0000FF);skin.setRGB(40,8,0xFFFF0000);
        var head=ImageIO.read(new ByteArrayInputStream(HeadTextureSource.face(encode(skin))));assertEquals(0xFFFF0000,head.getRGB(0,0));
    }
    @Test void invalidOrOversizedImagesAreRejectedBeforeDecode()throws Exception{
        assertThrows(IOException.class,()->HeadTextureSource.face(new byte[]{1,2,3}));
        assertThrows(IOException.class,()->HeadTextureSource.face(encode(new BufferedImage(2048,1,BufferedImage.TYPE_INT_ARGB))));
    }
    @Test void usernameReuseOrOfflineTebexIdCannotAuthorizeNewOwner(){
        UUID stored=UUID.randomUUID(),current=UUID.randomUUID();
        var donor=new DonorEntry(stored,"Donor",BigDecimal.ONE,BigDecimal.ONE,1,1,1);
        assertTrue(new HeadTextureSource(Map.of(),3,n->Optional.of(current)).resolve(donor).isEmpty());
        var allowed=new HeadTextureSource(Map.of(stored,current),3,n->Optional.of(current)).resolve(donor).orElseThrow();assertEquals(current,allowed.uuid());
        assertTrue(new HeadTextureSource(Map.of(stored,current),3,n->Optional.of(UUID.randomUUID())).resolve(donor).isEmpty());
    }
    @Test void exactUuidAndCurrentNameCanGenerateWithoutManualBinding(){
        UUID id=UUID.randomUUID();var donor=new DonorEntry(id,"Donor",BigDecimal.ONE,BigDecimal.ONE,1,1,1);
        assertEquals(id,new HeadTextureSource(Map.of(),3,n->Optional.of(id)).resolve(donor).orElseThrow().uuid());
        assertTrue(new HeadTextureSource(Map.of(),3,n->Optional.empty()).resolve(donor).isEmpty());
    }
}
