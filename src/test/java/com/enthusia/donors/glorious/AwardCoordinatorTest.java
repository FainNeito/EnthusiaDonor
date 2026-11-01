package com.enthusia.donors.glorious;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class AwardCoordinatorTest {
    static final YearMonth MONTH=YearMonth.of(2026,10);
    static final UUID PLAYER=UUID.fromString("00000000-0000-0000-0000-000000000001");
    static MonthlyWinner.Decision winner() {return new MonthlyWinner.Decision(MonthlyWinner.Status.WINNER,PLAYER,new BigDecimal("30"));}
    static final class Journal implements AwardCoordinator.Journal {
        AwardDecision current;
        public AwardDecision decide(AwardDecision proposed) {if(current==null)current=proposed;return current;}
        public boolean transition(AwardDecision decision,AwardDecision.State expected,AwardDecision.State next) {
            if(current.state()!=expected)return false;
            current=new AwardDecision(current.month(),current.winner(),next);return true;
        }
    }
    @Test void successfulAwardIsNeverGrantedTwice() throws Exception {
        var journal=new Journal();var calls=new AtomicInteger();
        var coordinator=new AwardCoordinator(journal,player->{calls.incrementAndGet();return AwardCoordinator.GrantResult.SAVED;});
        assertEquals(AwardCoordinator.Result.AWARDED,coordinator.process(MONTH,winner()));
        assertEquals(AwardCoordinator.Result.ALREADY_RECORDED,coordinator.process(MONTH,winner()));
        assertEquals(1,calls.get());
    }
    @Test void uncertainSaveIsNotRetriedOnNextPoll() throws Exception {
        var journal=new Journal();var calls=new AtomicInteger();
        var coordinator=new AwardCoordinator(journal,player->{calls.incrementAndGet();throw new IllegalStateException("lost acknowledgement");});
        assertEquals(AwardCoordinator.Result.UNCERTAIN,coordinator.process(MONTH,winner()));
        assertEquals(AwardCoordinator.Result.UNCERTAIN,coordinator.process(MONTH,winner()));
        assertEquals(AwardDecision.State.UNCERTAIN,journal.current.state());assertEquals(1,calls.get());
    }
    @Test void preWriteHoldReturnsToReadyAndRetries() throws Exception {
        var journal=new Journal();var calls=new AtomicInteger();
        var coordinator=new AwardCoordinator(journal,p->calls.getAndIncrement()==0?AwardCoordinator.GrantResult.HELD:AwardCoordinator.GrantResult.SAVED);
        assertEquals(AwardCoordinator.Result.HELD,coordinator.process(MONTH,winner()));
        assertEquals(AwardDecision.State.READY,journal.current.state());
        assertEquals(AwardCoordinator.Result.AWARDED,coordinator.process(MONTH,winner()));
        assertEquals(2,calls.get());
    }
    @Test void heldDataNeverCreatesJournalOrTouchesRanks() throws Exception {
        var journal=new Journal();var calls=new AtomicInteger();
        var coordinator=new AwardCoordinator(journal,player->{calls.incrementAndGet();return AwardCoordinator.GrantResult.SAVED;});
        assertEquals(AwardCoordinator.Result.HELD,coordinator.process(MONTH,MonthlyWinner.Decision.hold()));
        assertNull(journal.current);assertEquals(0,calls.get());
    }
}
