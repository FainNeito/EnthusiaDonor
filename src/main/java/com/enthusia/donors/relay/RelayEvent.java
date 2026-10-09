package com.enthusia.donors.relay;

import com.google.gson.Gson;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Public rendered test chat only. Signature covers every field, with length-delimited strings. */
public record RelayEvent(int version,String source,String id,String backend,long createdAt,long expiresAt,
                         List<String> lines,String signature) {
    private static final Gson JSON=new Gson();
    public RelayEvent {
        if(version!=1 || !safeId(source) || !safeId(backend) || id==null || !id.matches("[a-z0-9_.:-]{1,128}")
                || createdAt<=0 || expiresAt<=createdAt || expiresAt-createdAt>900_000)
            throw new IllegalArgumentException("Invalid relay metadata");
        lines=List.copyOf(lines);
        if(lines.isEmpty() || lines.size()>32 || lines.stream().anyMatch(s -> s==null || s.length()>32_000)
                || lines.stream().mapToInt(String::length).sum()>128_000)
            throw new IllegalArgumentException("Invalid relay message size");
        if(signature==null || !signature.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid relay signature");
    }
    public static boolean safeId(String value) {return value!=null && value.matches("[a-z0-9_.-]{1,64}");}
    public static RelayEvent sign(String source,String id,String backend,Instant now,int ttlSeconds,List<String> lines,String key) {
        RelayEvent unsigned=new RelayEvent(1,source,id,backend,now.toEpochMilli(),Math.addExact(now.toEpochMilli(),ttlSeconds*1000L),lines,"0".repeat(64));
        return new RelayEvent(1,source,id,backend,unsigned.createdAt,unsigned.expiresAt,lines,unsigned.hmac(key));
    }
    public boolean verify(String expectedSource,String key,Instant now) {
        try {
            return source.equals(expectedSource) && now.toEpochMilli()<expiresAt && createdAt<=now.plusSeconds(60).toEpochMilli()
                    && MessageDigest.isEqual(signature.getBytes(StandardCharsets.US_ASCII),hmac(key).getBytes(StandardCharsets.US_ASCII));
        } catch(Exception invalid) {return false;}
    }
    private String hmac(String key) {
        if(key==null || key.getBytes(StandardCharsets.UTF_8).length<32)throw new IllegalArgumentException("Relay signing key must contain at least 32 bytes");
        try {
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            StringBuilder data=new StringBuilder();
            for(String part:List.of(Integer.toString(version),source,id,backend,Long.toString(createdAt),Long.toString(expiresAt),Integer.toString(lines.size())))append(data,part);
            for(String line:lines)append(data,line);
            return HexFormat.of().formatHex(mac.doFinal(data.toString().getBytes(StandardCharsets.UTF_8)));
        } catch(java.security.GeneralSecurityException error) {throw new IllegalStateException("Relay signing unavailable",error);}
    }
    private static void append(StringBuilder out,String value) {out.append(value.length()).append(':').append(value);}
    public String json() {return JSON.toJson(this);}
    public static RelayEvent parse(String json) {
        if(json==null || json.length()>1_000_000)throw new IllegalArgumentException("Oversized relay event");
        return Objects.requireNonNull(JSON.fromJson(json,RelayEvent.class));
    }
}
