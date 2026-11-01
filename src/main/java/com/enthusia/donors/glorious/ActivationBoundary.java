package com.enthusia.donors.glorious;

import java.time.*;
import java.util.Objects;

/** First eligible month is the month containing activation; historical backfill is excluded. */
public record ActivationBoundary(Instant activatedAt, ZoneId timezone) {
    public ActivationBoundary { Objects.requireNonNull(activatedAt); Objects.requireNonNull(timezone); }
    public boolean mayFinalize(YearMonth month, Instant now) {
        Objects.requireNonNull(month); Objects.requireNonNull(now);
        if (month.isBefore(YearMonth.from(activatedAt.atZone(timezone)))) return false;
        Instant end = month.plusMonths(1).atDay(1).atStartOfDay(timezone).toInstant();
        return end.isAfter(activatedAt) && !now.isBefore(end);
    }
}
