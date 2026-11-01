package com.enthusia.donors.glorious;

import java.time.YearMonth;
import java.util.Objects;
import java.util.UUID;

/** A durable one-month decision. GRANTING is intentionally not automatically retried after a crash. */
public record AwardDecision(YearMonth month, UUID winner, State state) {
    public enum State { READY, GRANTING, AWARDED, UNCERTAIN, NO_WINNER }
    public AwardDecision {
        Objects.requireNonNull(month); Objects.requireNonNull(state);
        if ((state == State.NO_WINNER) != (winner == null)) throw new IllegalArgumentException("Invalid award winner/state");
    }
    public static AwardDecision winner(YearMonth month, UUID player) { return new AwardDecision(month, Objects.requireNonNull(player), State.READY); }
    public static AwardDecision noWinner(YearMonth month) { return new AwardDecision(month, null, State.NO_WINNER); }
    public AwardDecision begin() {
        if (state != State.READY) throw new IllegalStateException("Only a ready award can begin");
        return new AwardDecision(month,winner,State.GRANTING);
    }
    public AwardDecision confirmed() {
        if (state != State.GRANTING) throw new IllegalStateException("Award was not claimed");
        return new AwardDecision(month,winner,State.AWARDED);
    }
    public AwardDecision uncertain() {
        if (state != State.GRANTING) throw new IllegalStateException("Award was not claimed");
        return new AwardDecision(month,winner,State.UNCERTAIN);
    }
}
