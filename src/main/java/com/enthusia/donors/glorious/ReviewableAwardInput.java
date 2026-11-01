package com.enthusia.donors.glorious;

import com.enthusia.donors.config.DonorsConfig;
import com.enthusia.donors.model.PaymentRecord;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;

/** Builds a private review queue and award input from the same filtered payment set. */
public final class ReviewableAwardInput {
    public record Prepared(MonthlyAwardCycle.Reconciliation reconciliation, Set<String> unverifiedPayments) {
        public Prepared { Objects.requireNonNull(reconciliation); unverifiedPayments=Set.copyOf(unverifiedPayments); }
    }
    private ReviewableAwardInput() { }
    public static Prepared prepare(YearMonth month, ZoneId zone, Instant started, Instant finished,
                                   boolean sourceVerified, List<PaymentRecord> payments, DonorsConfig config,
                                   Set<String> exclusions, Map<String,UUID> reviewedPayers) {
        Objects.requireNonNull(month);Objects.requireNonNull(zone);Objects.requireNonNull(reviewedPayers);
        List<MonthlyWinner.Payment> filtered=OfficialAwardPayments.from(payments,config,exclusions,reviewedPayers);
        Instant start=month.atDay(1).atStartOfDay(zone).toInstant();
        Instant end=month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
        boolean unsettled=payments.stream().anyMatch(p -> !p.createdAt().isBefore(start) && p.createdAt().isBefore(end)
                && p.status()!=null && p.status().trim().equalsIgnoreCase("pending"));
        Set<String> pending=new TreeSet<>();Map<UUID,UUID> verified=new HashMap<>();
        for(MonthlyWinner.Payment p:filtered) {
            if(p.paidAt().isBefore(start) || !p.paidAt().isBefore(end))continue;
            if(!p.payerVerified())pending.add(p.id());
            else verified.put(p.customerId(),p.customerId());
        }
        return new Prepared(new MonthlyAwardCycle.Reconciliation(started,finished,sourceVerified && !unsettled,filtered,verified),pending);
    }
}
