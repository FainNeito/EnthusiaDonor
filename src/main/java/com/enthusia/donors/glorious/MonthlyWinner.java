package com.enthusia.donors.glorious;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;

/** Pure closed-month decision; the caller supplies an official, complete, already-filtered payment set. */
public final class MonthlyWinner {
    public record Payment(String id, UUID customerId, BigDecimal amount, String currency, Instant paidAt, boolean payerVerified) {
        public Payment { Objects.requireNonNull(id); Objects.requireNonNull(customerId); Objects.requireNonNull(amount); Objects.requireNonNull(currency); Objects.requireNonNull(paidAt); }
    }
    public enum Status { WINNER, NO_WINNER, HOLD }
    public record Decision(Status status, UUID playerId, BigDecimal total) {
        static Decision hold() { return new Decision(Status.HOLD, null, BigDecimal.ZERO); }
        static Decision none() { return new Decision(Status.NO_WINNER, null, BigDecimal.ZERO); }
    }
    private record Total(UUID player, BigDecimal amount, Instant reached) { }
    private MonthlyWinner() { }

    public static Decision decide(YearMonth month, ZoneId zone, Instant reconciledAt, BigDecimal minimum,
                                  List<Payment> payments, Map<UUID, UUID> verifiedCustomers) {
        Objects.requireNonNull(month); Objects.requireNonNull(zone); Objects.requireNonNull(reconciledAt);
        Objects.requireNonNull(minimum); Objects.requireNonNull(payments); Objects.requireNonNull(verifiedCustomers);
        if (minimum.signum() < 0 || minimum.stripTrailingZeros().scale() > 2) throw new IllegalArgumentException("Invalid minimum");
        Instant start = month.atDay(1).atStartOfDay(zone).toInstant();
        Instant end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
        if (reconciledAt.isBefore(end)) return Decision.hold();
        Map<UUID, Total> totals = new HashMap<>(); Set<String> ids = new HashSet<>();
        for (Payment p : payments) {
            if (p.paidAt().isBefore(start) || !p.paidAt().isBefore(end)) continue;
            if (!p.payerVerified() || p.id().isBlank() || !ids.add(p.id()) || p.amount().signum() <= 0
                    || p.amount().stripTrailingZeros().scale() > 2 || !p.currency().equalsIgnoreCase("USD")) return Decision.hold();
            UUID player = verifiedCustomers.get(p.customerId());
            if (player == null) return Decision.hold();
            Total previous = totals.get(player);
            totals.put(player, new Total(player, (previous == null ? BigDecimal.ZERO : previous.amount()).add(p.amount()),
                    previous == null || p.paidAt().isAfter(previous.reached()) ? p.paidAt() : previous.reached()));
        }
        Total top = totals.values().stream().sorted(Comparator.comparing(Total::amount).reversed()
                .thenComparing(Total::reached).thenComparing(t -> t.player().toString())).findFirst().orElse(null);
        if (top == null || top.amount().compareTo(minimum) < 0) return Decision.none();
        return new Decision(Status.WINNER, top.player(), top.amount());
    }
}
