package com.enthusia.donors.tebex;

import com.enthusia.donors.model.PaymentRecord;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Two matching strict paginated scans; this is not a transactional Tebex snapshot. */
public final class StableAwardScan {
    private final Instant startedAt,finishedAt;
    private final List<PaymentRecord> payments;
    private final byte[] sourceDigest;
    StableAwardScan(Instant startedAt,Instant finishedAt,List<PaymentRecord> payments,String apiKey) {
        this.startedAt=Objects.requireNonNull(startedAt);this.finishedAt=Objects.requireNonNull(finishedAt);
        if(finishedAt.isBefore(startedAt))throw new IllegalArgumentException("Invalid scan interval");
        this.payments=List.copyOf(payments);
        this.sourceDigest=digest(apiKey);
    }
    public Instant startedAt(){return startedAt;}
    public Instant finishedAt(){return finishedAt;}
    public List<PaymentRecord> payments(){return payments;}
    public boolean belongsTo(String apiKey){return apiKey!=null && !apiKey.isBlank() && MessageDigest.isEqual(sourceDigest,digest(apiKey));}
    private static byte[] digest(String apiKey) {
        if(apiKey==null || apiKey.isBlank())throw new IllegalArgumentException("Award scan needs an API key");
        try {return MessageDigest.getInstance("SHA-256").digest(apiKey.getBytes(StandardCharsets.UTF_8));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
