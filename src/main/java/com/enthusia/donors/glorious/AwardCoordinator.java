package com.enthusia.donors.glorious;

import java.util.Objects;
import java.util.UUID;

/** Domain orchestration; adapters provide durable, lease-fenced compare-and-set operations. */
public final class AwardCoordinator {
    public interface Journal {
        AwardDecision decide(AwardDecision proposed) throws Exception;
        boolean transition(AwardDecision decision, AwardDecision.State expected, AwardDecision.State next) throws Exception;
    }
    public enum GrantResult { SAVED, ALREADY_PRESENT, HELD, UNCERTAIN }
    @FunctionalInterface public interface Grant { GrantResult apply(UUID player) throws Exception; }
    public enum Result { HELD, NO_WINNER, ALREADY_RECORDED, AWARDED, UNCERTAIN }
    private final Journal journal;
    private final Grant grant;
    public AwardCoordinator(Journal journal,Grant grant) {this.journal=Objects.requireNonNull(journal);this.grant=Objects.requireNonNull(grant);}

    public Result process(java.time.YearMonth month, MonthlyWinner.Decision winner) throws Exception {
        Objects.requireNonNull(month);Objects.requireNonNull(winner);
        if(winner.status()==MonthlyWinner.Status.HOLD)return Result.HELD;
        AwardDecision proposed=winner.status()==MonthlyWinner.Status.NO_WINNER ? AwardDecision.noWinner(month) : AwardDecision.winner(month,winner.playerId());
        AwardDecision stored=journal.decide(proposed);
        if(!stored.month().equals(proposed.month()) || !Objects.equals(stored.winner(),proposed.winner()))throw new IllegalStateException("Stored winner conflict");
        if(stored.state()==AwardDecision.State.NO_WINNER)return Result.NO_WINNER;
        if(stored.state()==AwardDecision.State.GRANTING || stored.state()==AwardDecision.State.UNCERTAIN)return Result.UNCERTAIN;
        if(stored.state()!=AwardDecision.State.READY)return Result.ALREADY_RECORDED;
        if(!journal.transition(stored,AwardDecision.State.READY,AwardDecision.State.GRANTING))return Result.UNCERTAIN;
        GrantResult outcome;
        try {outcome=Objects.requireNonNull(grant.apply(stored.winner()));}
        catch(InterruptedException interrupted) {Thread.currentThread().interrupt();outcome=GrantResult.UNCERTAIN;}
        catch(Exception uncertain) {outcome=GrantResult.UNCERTAIN;}
        AwardDecision.State next=(outcome==GrantResult.SAVED || outcome==GrantResult.ALREADY_PRESENT)
                ? AwardDecision.State.AWARDED : outcome==GrantResult.HELD ? AwardDecision.State.READY : AwardDecision.State.UNCERTAIN;
        // A lost lease or failed state write leaves GRANTING as an explicit manual-review hold.
        if(!journal.transition(stored,AwardDecision.State.GRANTING,next))return Result.UNCERTAIN;
        return next==AwardDecision.State.AWARDED?Result.AWARDED:next==AwardDecision.State.READY?Result.HELD:Result.UNCERTAIN;
    }
}
