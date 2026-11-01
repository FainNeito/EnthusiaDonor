package com.enthusia.donors.glorious;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Coordinates a closed month only after the caller has verified the entire source scan. */
public final class MonthlyAwardCycle {
    public record Reconciliation(Instant startedAt, Instant finishedAt, boolean verifiedComplete,
                                 List<MonthlyWinner.Payment> payments, Map<UUID, UUID> verifiedCustomers) {
        public Reconciliation {
            Objects.requireNonNull(startedAt); Objects.requireNonNull(finishedAt);
            payments = List.copyOf(payments); verifiedCustomers = Map.copyOf(verifiedCustomers);
            if (finishedAt.isBefore(startedAt)) throw new IllegalArgumentException("Invalid reconciliation interval");
        }
    }

    private final AwardCoordinator coordinator;
    public MonthlyAwardCycle(AwardCoordinator coordinator) { this.coordinator = Objects.requireNonNull(coordinator); }

    public AwardCoordinator.Result process(ActivationBoundary boundary, YearMonth month, Instant now,
                                           BigDecimal minimum, Reconciliation input) throws Exception {
        Objects.requireNonNull(boundary); Objects.requireNonNull(month); Objects.requireNonNull(now);
        Objects.requireNonNull(minimum); Objects.requireNonNull(input);
        Instant end = month.plusMonths(1).atDay(1).atStartOfDay(boundary.timezone()).toInstant();
        if (!boundary.mayFinalize(month, now) || !input.verifiedComplete()
                || input.startedAt().isBefore(end) || input.finishedAt().isAfter(now)) {
            return AwardCoordinator.Result.HELD;
        }
        var winner = MonthlyWinner.decide(month, boundary.timezone(), input.finishedAt(), minimum,
                input.payments(), input.verifiedCustomers());
        return coordinator.process(month, winner);
    }
}
