package com.enthusia.donors.infrastructure.glorious;

import com.enthusia.donors.glorious.ActivationBoundary;
import com.enthusia.donors.glorious.AwardCoordinator;
import com.enthusia.donors.tebex.StableAwardScan;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Bounded publisher cycle; the caller supplies a fenced service and an asynchronous strict scan. */
public final class GloriousPublisherCycle implements AutoCloseable {
    @FunctionalInterface public interface Evaluation {
        AwardCoordinator.Result evaluate(YearMonth month, Instant now, StableAwardScan scan) throws Exception;
    }
    public enum Result { BUSY, STOPPED, WAITING, HELD, COMPLETE }
    private final ActivationBoundary boundary;
    private final BooleanSupplier leaseValid;
    private final Supplier<CompletableFuture<StableAwardScan>> scan;
    private final Evaluation evaluation;
    private final Executor executor;
    private final Clock clock;
    private final AtomicBoolean inFlight=new AtomicBoolean();
    private volatile boolean closed;
    public GloriousPublisherCycle(ActivationBoundary boundary,BooleanSupplier leaseValid,
                                  Supplier<CompletableFuture<StableAwardScan>> scan,Evaluation evaluation,
                                  Executor executor,Clock clock) {
        this.boundary=Objects.requireNonNull(boundary);this.leaseValid=Objects.requireNonNull(leaseValid);
        this.scan=Objects.requireNonNull(scan);this.evaluation=Objects.requireNonNull(evaluation);
        this.executor=Objects.requireNonNull(executor);this.clock=Objects.requireNonNull(clock);
    }
    public CompletableFuture<Result> tick() {
        if(closed || !leaseValid.getAsBoolean())return CompletableFuture.completedFuture(Result.STOPPED);
        if(!inFlight.compareAndSet(false,true))return CompletableFuture.completedFuture(Result.BUSY);
        try {
            Instant now=clock.instant();
            YearMonth first=YearMonth.from(boundary.activatedAt().atZone(boundary.timezone()));
            YearMonth last=YearMonth.from(now.atZone(boundary.timezone())).minusMonths(1);
            if(last.isBefore(first)) {inFlight.set(false);return CompletableFuture.completedFuture(Result.WAITING);}
            if(ChronoUnit.MONTHS.between(first,last)>120) {inFlight.set(false);return CompletableFuture.completedFuture(Result.HELD);}
            return scan.get().thenApplyAsync(evidence -> {
                try {
                    for(YearMonth month=first;!month.isAfter(last);month=month.plusMonths(1)) {
                        if(closed || !leaseValid.getAsBoolean())return Result.STOPPED;
                        AwardCoordinator.Result result=evaluation.evaluate(month,clock.instant(),evidence);
                        if(result==AwardCoordinator.Result.HELD || result==AwardCoordinator.Result.UNCERTAIN)return Result.HELD;
                    }
                    return Result.COMPLETE;
                } catch(Exception failure) {throw new CompletionException(failure);}
            },executor).whenComplete((result,error)->inFlight.set(false));
        } catch(Exception failure) {inFlight.set(false);return CompletableFuture.failedFuture(failure);}
    }
    @Override public void close(){closed=true;}
}
