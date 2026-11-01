package com.enthusia.donors.glorious;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MonthlyAwardCycleTest {
    private static final ZoneId ZONE = ZoneId.of("UTC");
    private static final YearMonth MONTH = YearMonth.of(2026, 11);
    private static final Instant END = Instant.parse("2026-12-01T00:00:00Z");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final ActivationBoundary boundary = new ActivationBoundary(Instant.parse("2026-11-01T12:00:00Z"), ZONE);
    private int journalCalls, grants;
    private final AwardCoordinator.Journal journal = new AwardCoordinator.Journal() {
        public AwardDecision decide(AwardDecision proposed) { journalCalls++; return proposed; }
        public boolean transition(AwardDecision decision, AwardDecision.State expected, AwardDecision.State next) { return true; }
    };
    private final MonthlyAwardCycle cycle = new MonthlyAwardCycle(new AwardCoordinator(journal, player -> {
        grants++; return AwardCoordinator.GrantResult.SAVED;
    }));
    private MonthlyAwardCycle.Reconciliation input(Instant start, Instant finish, boolean complete, boolean verified) {
        var payment = new MonthlyWinner.Payment("p1", PLAYER, new BigDecimal("30.00"), "USD", END.minusSeconds(1), verified);
        return new MonthlyAwardCycle.Reconciliation(start, finish, complete, List.of(payment), Map.of(PLAYER, PLAYER));
    }
    @Test void verifiedScanStartedAfterCloseAwardsOnce() throws Exception {
        var result = cycle.process(boundary, MONTH, END.plusSeconds(2), new BigDecimal("30"),
                input(END, END.plusSeconds(1), true, true));
        assertEquals(AwardCoordinator.Result.AWARDED, result); assertEquals(1, journalCalls); assertEquals(1, grants);
    }
    @Test void scanStartedBeforeCloseIsHeldEvenWhenItFinishesAfter() throws Exception {
        var result = cycle.process(boundary, MONTH, END.plusSeconds(2), new BigDecimal("30"),
                input(END.minusSeconds(1), END.plusSeconds(1), true, true));
        assertEquals(AwardCoordinator.Result.HELD, result); assertEquals(0, journalCalls); assertEquals(0, grants);
    }
    @Test void incompleteUnverifiedOrFutureScansHaveNoSideEffects() throws Exception {
        assertEquals(AwardCoordinator.Result.HELD, cycle.process(boundary, MONTH, END.plusSeconds(2), new BigDecimal("30"), input(END, END.plusSeconds(1), false, true)));
        assertEquals(AwardCoordinator.Result.HELD, cycle.process(boundary, MONTH, END.plusSeconds(2), new BigDecimal("30"), input(END, END.plusSeconds(1), true, false)));
        assertEquals(AwardCoordinator.Result.HELD, cycle.process(boundary, MONTH, END, new BigDecimal("30"), input(END, END.plusSeconds(1), true, true)));
        assertEquals(0, journalCalls); assertEquals(0, grants);
    }
    @Test void monthBeforeActivationNeverAwarded() throws Exception {
        assertEquals(AwardCoordinator.Result.HELD, cycle.process(boundary, MONTH.minusMonths(1), END, new BigDecimal("30"), input(END, END, true, true)));
        assertEquals(0, journalCalls); assertEquals(0, grants);
    }
}
