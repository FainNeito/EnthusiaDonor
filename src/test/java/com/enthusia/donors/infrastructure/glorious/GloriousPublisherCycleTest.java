package com.enthusia.donors.infrastructure.glorious;

import com.enthusia.donors.glorious.*;
import com.enthusia.donors.tebex.AwardScanFixture;
import com.enthusia.donors.tebex.StableAwardScan;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class GloriousPublisherCycleTest {
    private static final ZoneId UTC=ZoneOffset.UTC;
    private static final Instant NOW=Instant.parse("2027-01-03T00:00:00Z");
    private static final ActivationBoundary BOUNDARY=new ActivationBoundary(Instant.parse("2026-11-15T00:00:00Z"),UTC);
    private static StableAwardScan scan(){return AwardScanFixture.create(NOW.minusSeconds(1),NOW,List.of());}
    @Test void processesOnlyClosedMonthsAfterActivationAndStopsOnHold() {
        var months=new ArrayList<YearMonth>();
        var cycle=new GloriousPublisherCycle(BOUNDARY,()->true,()->CompletableFuture.completedFuture(scan()),
                (month,now,evidence)->{months.add(month);return AwardCoordinator.Result.HELD;},Runnable::run,Clock.fixed(NOW,UTC));
        assertEquals(GloriousPublisherCycle.Result.HELD,cycle.tick().join());
        assertEquals(List.of(YearMonth.of(2026,11)),months);
    }
    @Test void inFlightAndLeaseLossPreventDuplicateOrStaleEvaluation() {
        var alive=new AtomicBoolean(true);var pending=new CompletableFuture<StableAwardScan>();var calls=new AtomicInteger();
        var cycle=new GloriousPublisherCycle(BOUNDARY,alive::get,()->pending,
                (month,now,evidence)->{calls.incrementAndGet();return AwardCoordinator.Result.AWARDED;},Runnable::run,Clock.fixed(NOW,UTC));
        var first=cycle.tick();assertEquals(GloriousPublisherCycle.Result.BUSY,cycle.tick().join());
        alive.set(false);pending.complete(scan());assertEquals(GloriousPublisherCycle.Result.STOPPED,first.join());assertEquals(0,calls.get());
    }
    @Test void noEligibleMonthOrClosedWorkerDoesNotScan() {
        var calls=new AtomicInteger();
        var cycle=new GloriousPublisherCycle(BOUNDARY,()->true,()->{calls.incrementAndGet();return CompletableFuture.completedFuture(scan());},
                (month,now,evidence)->AwardCoordinator.Result.AWARDED,Runnable::run,Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"),UTC));
        assertEquals(GloriousPublisherCycle.Result.WAITING,cycle.tick().join());cycle.close();
        assertEquals(GloriousPublisherCycle.Result.STOPPED,cycle.tick().join());assertEquals(0,calls.get());
    }
    @Test void failedScanDoesNotEvaluateAndNextTickCanRetry() {
        var scans=new AtomicInteger();var evaluations=new AtomicInteger();
        var cycle=new GloriousPublisherCycle(BOUNDARY,()->true,()->scans.incrementAndGet()==1
                ?CompletableFuture.failedFuture(new IllegalStateException("unavailable")):CompletableFuture.completedFuture(scan()),
                (month,now,evidence)->{evaluations.incrementAndGet();return AwardCoordinator.Result.NO_WINNER;},Runnable::run,Clock.fixed(NOW,UTC));
        assertThrows(CompletionException.class,()->cycle.tick().join());assertEquals(0,evaluations.get());
        assertEquals(GloriousPublisherCycle.Result.COMPLETE,cycle.tick().join());assertEquals(2,evaluations.get());
    }
}
