package com.enthusia.donors.infrastructure.glorious;

import com.enthusia.donors.glorious.*;
import java.time.ZoneId;
import java.util.Objects;

/** One captured publisher-lease epoch; the JDBC store rejects an expired or replaced lease. */
public final class PublisherAwardJournal implements AwardCoordinator.Journal {
    private final JdbcAwardJournal journal; private final String token; private final long epoch; private final ZoneId zone;
    public PublisherAwardJournal(JdbcAwardJournal journal,String token,long epoch,ZoneId zone) {
        this.journal=Objects.requireNonNull(journal);this.token=Objects.requireNonNull(token);this.epoch=epoch;this.zone=Objects.requireNonNull(zone);
    }
    @Override public AwardDecision decide(AwardDecision proposed) throws Exception {return journal.decide(token,epoch,zone,proposed);}
    @Override public boolean transition(AwardDecision decision,AwardDecision.State expected,AwardDecision.State next) throws Exception {
        return journal.transition(token,epoch,decision.month(),expected,next);
    }
}
