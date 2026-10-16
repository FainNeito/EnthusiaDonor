package com.enthusia.donors.infrastructure.heads;

import com.enthusia.donors.heads.HeadEmojiQueue;
import com.enthusia.donors.model.DonorEntry;
import com.enthusia.donors.sandbox.skin.MojangProfiles;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.HttpURLConnection;
import java.util.*;
import java.util.function.Function;
import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;

/** Exact profile ownership, bounded official HTTPS textures; no authlib refresh. */
public final class HeadTextureSource implements HeadEmojiQueue.Identity,NexoGlyphFiles.Pictures {
    private final Map<UUID,UUID> verified;private final int timeout;
    private final Function<String,Optional<UUID>> currentNames;
    public HeadTextureSource(Map<UUID,UUID> verified,int timeout){this(verified,timeout,name->MojangProfiles.uuidByName(name,timeout));}
    HeadTextureSource(Map<UUID,UUID> verified,int timeout,Function<String,Optional<UUID>> currentNames){this.verified=Map.copyOf(verified);this.timeout=timeout;this.currentNames=currentNames;}
    @Override public Optional<HeadEmojiQueue.Owner> resolve(DonorEntry donor){
        Optional<UUID> current=currentNames.apply(donor.name());
        UUID expected=verified.getOrDefault(donor.uuid(),donor.uuid());
        if(current.isEmpty()||!current.get().equals(expected))return Optional.empty();
        return Optional.of(new HeadEmojiQueue.Owner(expected,donor.name()));
    }
    @Override public byte[] load(UUID owner)throws Exception {
        var url=MojangProfiles.texture(owner,timeout).orElseThrow(()->new IOException("Profile texture unavailable"));
        var connection=(HttpURLConnection)url.openConnection();
        connection.setConnectTimeout(timeout*1000);connection.setReadTimeout(timeout*1000);connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent","EnthusiaDonors-HeadQueue/1");
        byte[] bytes;
        try {
            if(connection.getResponseCode()!=200)throw new IOException("Texture unavailable");
            try(var input=connection.getInputStream()){bytes=input.readNBytes(1_048_577);if(bytes.length>1_048_576)throw new IOException("Texture too large");}
        }finally{connection.disconnect();}
        return face(bytes);
    }
    static byte[] face(byte[] bytes)throws Exception {
        try(ImageInputStream input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){
            Iterator<ImageReader> readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw new IOException("Invalid skin image");
            ImageReader reader=readers.next();
            try {
                reader.setInput(input);int w=reader.getWidth(0),h=reader.getHeight(0);
                if(w<64||w>1024||w%64!=0||(h!=w&&h!=w/2))throw new IOException("Invalid skin dimensions");
                var skin=reader.read(0);int scale=w/64;boolean overlay=false;
                for(int y=0;y<16*scale;y++)for(int x=32*scale;x<64*scale;x++)if((skin.getRGB(x,y)>>>24)<255)overlay=true;
                var head=new BufferedImage(8,8,BufferedImage.TYPE_INT_ARGB);
                for(int y=0;y<8;y++)for(int x=0;x<8;x++){
                    int base=skin.getRGB((8+x)*scale,(8+y)*scale),hat=skin.getRGB((40+x)*scale,(8+y)*scale);
                    int alpha=overlay?(hat>>>24):0,rgb=0;
                    for(int shift:new int[]{16,8,0})rgb|=((((hat>>>shift)&255)*alpha+((base>>>shift)&255)*(255-alpha)+127)/255)<<shift;
                    head.setRGB(x,y,0xFF000000|rgb);
                }
                var output=new ByteArrayOutputStream();if(!ImageIO.write(head,"PNG",output))throw new IOException("PNG encoder missing");return output.toByteArray();
            }finally{reader.dispose();}
        }
    }
}
