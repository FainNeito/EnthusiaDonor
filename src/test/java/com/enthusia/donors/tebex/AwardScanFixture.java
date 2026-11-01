package com.enthusia.donors.tebex;

import com.enthusia.donors.model.PaymentRecord;
import java.time.Instant;
import java.util.List;

/** Constructs synthetic scan evidence for isolated tests only. */
public final class AwardScanFixture {
    private AwardScanFixture() { }
    public static StableAwardScan create(Instant start,Instant end,List<PaymentRecord> payments) {
        return new StableAwardScan(start,end,payments,"test");
    }
    public static StableAwardScan fromSource(Instant start,Instant end,List<PaymentRecord> payments,String key) {
        return new StableAwardScan(start,end,payments,key);
    }
}
