package com.enthusia.donors.sandbox.skin;

import java.awt.image.BufferedImage;

/** Vanilla face (8,8) plus hat (40,8), scaled for HD textures. Never mutates the source image. */
public final class FacePixels {
    private FacePixels() { }
    public static int[] extract(BufferedImage image) {
        int w=image.getWidth(), h=image.getHeight();
        if(w<64 || w>1024 || w%64!=0 || (h!=w && h!=w/2)) throw new IllegalArgumentException("Unsupported skin dimensions.");
        int scale=w/64; int[] out=new int[64];
        for(int y=0;y<8;y++) for(int x=0;x<8;x++) {
            int base=image.getRGB((8+x)*scale,(8+y)*scale);
            int overlay=image.getRGB((40+x)*scale,(8+y)*scale);
            out[y*8+x]=blend(base,overlay);
        }
        return out;
    }
    public static int blend(int base,int overlay) {
        int a=(overlay>>>24)&255; int result=0;
        for(int shift : new int[]{16,8,0}) {
            int b=(base>>>shift)&255, o=(overlay>>>shift)&255;
            result|=((o*a+b*(255-a)+127)/255)<<shift;
        }
        return result;
    }
    /** Neutral original fallback, not presented as somebody else's real skin. */
    public static int[] fallback() {
        int[] p=new int[64]; java.util.Arrays.fill(p,0x9BA5B5);
        for(int i=0;i<16;i++)p[i]=0x566274;
        p[26]=p[29]=0x293348; p[43]=p[44]=0x657284;
        return p;
    }
}
