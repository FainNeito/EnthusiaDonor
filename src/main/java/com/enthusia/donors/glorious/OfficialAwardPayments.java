package com.enthusia.donors.glorious;

import com.enthusia.donors.config.DonorsConfig;
import com.enthusia.donors.model.PaymentRecord;
import com.enthusia.donors.storage.DonorRepository;
import java.util.*;

/** Translates a complete official reconciliation, excluding reversals regardless of display settings. */
public final class OfficialAwardPayments {
    private OfficialAwardPayments() { }
    public static List<MonthlyWinner.Payment> from(List<PaymentRecord> officialPayments, DonorsConfig config, Set<String> resolvedExclusions,
                                                    Map<String, UUID> verifiedPayerCustomers) {
        Objects.requireNonNull(officialPayments); Objects.requireNonNull(config); Objects.requireNonNull(resolvedExclusions);
        Objects.requireNonNull(verifiedPayerCustomers);
        List<MonthlyWinner.Payment> result = new ArrayList<>();
        for (PaymentRecord p : officialPayments) {
            String status = p.status() == null ? "" : p.status().trim().toLowerCase(Locale.ROOT).replace(' ', '_');
            if (p.refundedOrChargeback() || p.manualPayment() || p.amount() == null || p.amount().signum() <= 0
                    || !Set.of("complete", "completed", "successful", "success", "paid").contains(status)) continue;
            if (!DonorRepository.isCountable(p.amount(), p.status(), false, false, new HashSet<>(p.packageIds()),
                    p.paymentIdHash(), resolvedExclusions, config)) continue;
            UUID verified = verifiedPayerCustomers.get(p.paymentIdHash());
            result.add(new MonthlyWinner.Payment(p.paymentIdHash(), verified == null ? p.playerUuid() : verified, p.amount(),
                    p.currency() == null ? "" : p.currency(), p.createdAt(), verified != null));
        }
        return List.copyOf(result);
    }
}
