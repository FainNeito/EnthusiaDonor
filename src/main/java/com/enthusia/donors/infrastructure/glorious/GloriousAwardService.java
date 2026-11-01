package com.enthusia.donors.infrastructure.glorious;

import com.enthusia.donors.config.DonorsConfig;
import com.enthusia.donors.glorious.*;
import com.enthusia.donors.tebex.StableAwardScan;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** One publisher lease's award workflow. Call start at activation, before any month closes. */
public final class GloriousAwardService {
    public record Outcome(AwardCoordinator.Result result, Set<String> unverifiedPayments) {
        public Outcome { Objects.requireNonNull(result); unverifiedPayments=Set.copyOf(unverifiedPayments); }
    }
    private final JdbcAwardJournal journal;
    private final String token;
    private final long epoch;
    private final ActivationBoundary boundary;
    private final MonthlyAwardCycle cycle;

    private GloriousAwardService(JdbcAwardJournal journal,String token,long epoch,ActivationBoundary boundary,AwardCoordinator.Grant grant) {
        this.journal=journal;this.token=token;this.epoch=epoch;this.boundary=boundary;
        this.cycle=new MonthlyAwardCycle(new AwardCoordinator(new PublisherAwardJournal(journal,token,epoch,boundary.timezone()),grant));
    }
    /** Persists the first activation boundary using database time and checks the active lease. */
    public static GloriousAwardService start(JdbcAwardJournal journal,String token,long epoch,ZoneId timezone,AwardCoordinator.Grant grant) throws Exception {
        Objects.requireNonNull(journal);Objects.requireNonNull(grant);
        return new GloriousAwardService(journal,token,epoch,journal.activate(token,epoch,timezone),grant);
    }
    public ActivationBoundary boundary() {return boundary;}
    public Optional<AwardDecision> status(YearMonth month) throws Exception {return journal.findDecision(token,epoch,month);}
    /** Caller must independently verify durable permanent membership before attesting. */
    public void confirmUncertain(YearMonth month,UUID winner,UUID reviewer,String evidenceDigest) throws Exception {
        journal.confirmUncertain(token,epoch,month,winner,reviewer,evidenceDigest);
    }

    /** Private review queue; this operation never decides a winner or grants a rank. */
    public Set<String> pending(YearMonth month,Instant now,StableAwardScan scan,DonorsConfig config,Set<String> exclusions) throws Exception {
        checkReviewWindow(month,now,scan,config);
        if(journal.findDecision(token,epoch,month).isPresent())throw new IllegalStateException("Month already has an award decision");
        var reviewed=journal.reviewedPayers(token,epoch,month);
        return ReviewableAwardInput.prepare(month,boundary.timezone(),scan.startedAt(),scan.finishedAt(),true,
                scan.payments(),config,exclusions,reviewed).unverifiedPayments();
    }

    /** Caller must authorize the reviewer and independently verify the payer and evidence. */
    public void review(YearMonth month,Instant now,StableAwardScan scan,DonorsConfig config,Set<String> exclusions,
                       String paymentHash,UUID payer,UUID reviewer,String evidenceDigest) throws Exception {
        checkReviewWindow(month,now,scan,config);
        var input=ReviewableAwardInput.prepare(month,boundary.timezone(),scan.startedAt(),scan.finishedAt(),true,
                scan.payments(),config,exclusions,Map.of());
        if(!input.unverifiedPayments().contains(paymentHash))throw new IllegalArgumentException("Payment is not eligible in this month");
        journal.recordPayerReview(token,epoch,month,paymentHash,payer,reviewer,evidenceDigest);
    }

    /** Requires a scan produced by TebexClient; no unreviewed payer can grant. */
    public Outcome evaluate(YearMonth month,Instant now,StableAwardScan scan,
                            DonorsConfig config,Set<String> exclusions,BigDecimal minimum) throws Exception {
        checkSource(scan,config);
        var existing=journal.findDecision(token,epoch,month);
        if(existing.isPresent()) {
            var state=existing.get().state();
            if(state==AwardDecision.State.AWARDED)return new Outcome(AwardCoordinator.Result.ALREADY_RECORDED,Set.of());
            if(state==AwardDecision.State.NO_WINNER)return new Outcome(AwardCoordinator.Result.NO_WINNER,Set.of());
            if(state==AwardDecision.State.GRANTING || state==AwardDecision.State.UNCERTAIN)
                return new Outcome(AwardCoordinator.Result.UNCERTAIN,Set.of());
        }
        var reviewed=journal.reviewedPayers(token,epoch,month);
        var prepared=ReviewableAwardInput.prepare(month,boundary.timezone(),scan.startedAt(),scan.finishedAt(),true,scan.payments(),config,exclusions,reviewed);
        return new Outcome(cycle.process(boundary,month,now,minimum,prepared.reconciliation()),prepared.unverifiedPayments());
    }
    private void checkSource(StableAwardScan scan,DonorsConfig config) {
        Objects.requireNonNull(scan);Objects.requireNonNull(config);
        if(!scan.belongsTo(config.tebexApiKey()) || !boundary.timezone().equals(config.timezone()))
            throw new IllegalArgumentException("Award scan source or timezone does not match configuration");
    }
    private void checkReviewWindow(YearMonth month,Instant now,StableAwardScan scan,DonorsConfig config) {
        Objects.requireNonNull(month);Objects.requireNonNull(now);checkSource(scan,config);
        Instant end=month.plusMonths(1).atDay(1).atStartOfDay(boundary.timezone()).toInstant();
        if(!boundary.mayFinalize(month,now) || scan.startedAt().isBefore(end) || scan.finishedAt().isAfter(now))
            throw new IllegalArgumentException("Review requires an eligible closed month and a post-close scan");
    }
}
