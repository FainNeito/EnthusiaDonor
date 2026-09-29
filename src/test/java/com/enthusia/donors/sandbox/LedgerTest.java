package com.enthusia.donors.sandbox;

import com.enthusia.donors.sandbox.domain.*;
import com.enthusia.donors.sandbox.domain.Domain.*;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LedgerTest {
 State state;Person alice=Person.synthetic("Alice"),bob=Person.synthetic("Bob"),eve=Person.synthetic("Eve");
 @BeforeEach void setup(){state=Ledger.empty(Instant.parse("2026-09-20T12:00:00Z"),ZoneId.of("America/Chicago"));}
 void pay(String id,Person buyer,Person recipient,Product product,Kind kind,long amount,long at){Ledger.add(state,id,buyer,recipient,product,kind,amount,at,30);}
 void avid(String id,Person who,long amount){pay(id,who,who,Product.AVID,Kind.PURCHASE,amount,state.now);}
 @Test void giftCreditsBuyerNotRecipient(){pay("gift",alice,bob,Product.AVID,Kind.GIFT,2000,state.now);View v=Ledger.view(state);assertEquals(2000,v.profile(alice.uuid()).alltimeCents());assertEquals(0,v.profile(bob.uuid()).alltimeCents());assertTrue(v.profile(bob.uuid()).avid());assertFalse(v.profile(alice.uuid()).avid());}
 @Test void giftHasBothDirections(){pay("gift",alice,bob,Product.DEVOTEE,Kind.GIFT,500,state.now);View v=Ledger.view(state);assertEquals(1,v.profile(alice.uuid()).giftsSent());assertEquals(1,v.profile(bob.uuid()).giftsReceived());assertEquals("gift",v.history(bob.uuid(),"received").getFirst().id());}
 @Test void recipientWithNoSpendingHasProfile(){pay("gift",alice,bob,Product.AVID,Kind.GIFT,2000,state.now);assertNotNull(Ledger.view(state).profile(bob.uuid()));assertEquals(0,Ledger.view(state).profile(bob.uuid()).alltimeRank());}
 @Test void selfGiftRejectedWithoutMutation(){assertThrows(IllegalArgumentException.class,()->pay("gift",alice,alice,Product.AVID,Kind.GIFT,2000,state.now));assertTrue(state.payments.isEmpty());assertTrue(state.people.isEmpty());}
 @Test void duplicateDoesNotAddMoneyOrGiftCount(){pay("gift",alice,bob,Product.AVID,Kind.GIFT,2000,state.now);assertFalse(Ledger.add(state,"gift",alice,bob,Product.AVID,Kind.GIFT,2000,state.now,30));assertEquals(2000,Ledger.view(state).profile(alice.uuid()).alltimeCents());assertEquals(1,Ledger.view(state).profile(alice.uuid()).giftsSent());}
 @Test void duplicateCannotChangeStatus(){avid("p",alice,2000);Ledger.reverse(state,"p",Status.REFUNDED);assertFalse(Ledger.add(state,"p",alice,alice,Product.AVID,Kind.PURCHASE,2000,state.now,30));assertEquals(Status.REFUNDED,state.payments.get("p").status());}
 @Test void zeroDollarDoesNotWinGlorious(){avid("free",alice,0);assertTrue(Ledger.rank(state,null).isEmpty());assertTrue(Ledger.closeMonth(state).isEmpty());}
 @Test void negativeAmountRejected(){assertThrows(IllegalArgumentException.class,()->avid("bad",alice,-1));}
 @Test void futurePaymentsRejected(){assertThrows(IllegalArgumentException.class,()->pay("future",alice,alice,Product.AVID,Kind.PURCHASE,20,state.now+1));}
 @Test void actualMonthlyWinnerOnly(){avid("small",alice,500);avid("large",bob,2000);assertFalse(Ledger.view(state).profile(alice.uuid()).glorious());Award winner=Ledger.closeMonth(state).getFirst();assertEquals(bob.uuid(),winner.winner());assertFalse(Ledger.view(state).profile(alice.uuid()).glorious());assertTrue(Ledger.view(state).profile(bob.uuid()).glorious());}
 @Test void oldWinnerKeepsGlorious(){avid("a",alice,2000);Ledger.closeMonth(state);avid("b",bob,3000);Ledger.closeMonth(state);assertTrue(Ledger.view(state).profile(alice.uuid()).glorious());assertTrue(Ledger.view(state).profile(bob.uuid()).glorious());}
 @Test void sameWinnerMultipleMonthsKeepsTwoVictories(){avid("a",alice,2000);Ledger.closeMonth(state);avid("b",alice,3000);Ledger.closeMonth(state);Profile p=Ledger.view(state).profile(alice.uuid());assertEquals(2,p.monthlyWins());assertEquals(2,p.awards().size());}
 @Test void alreadyClosedMonthNeverReawards(){avid("a",alice,2000);assertEquals(1,Ledger.closeMonth(state).size());assertTrue(Ledger.closeMonth(state).isEmpty());assertEquals(1,state.awards.size());}
 @Test void refundBeforeCloseChangesWinner(){avid("a",alice,2000);avid("b",bob,1000);Ledger.reverse(state,"a",Status.REFUNDED);assertEquals(bob.uuid(),Ledger.closeMonth(state).getFirst().winner());}
 @Test void refundAfterCloseDoesNotRemoveAward(){avid("a",alice,2000);Ledger.closeMonth(state);Ledger.reverse(state,"a",Status.REFUNDED);Profile p=Ledger.view(state).profile(alice.uuid());assertTrue(p.glorious());assertEquals(0,p.alltimeCents());assertEquals(2000,p.awards().getFirst().cents());}
 @Test void chargebackAfterCloseDoesNotRemoveAward(){avid("a",alice,2000);Ledger.closeMonth(state);Ledger.reverse(state,"a",Status.CHARGEBACK);assertTrue(Ledger.view(state).profile(alice.uuid()).glorious());}
 @Test void manualAwardIsNotMonthlyWin(){Ledger.manualGlorious(state,alice);Ledger.manualGlorious(state,alice);Profile p=Ledger.view(state).profile(alice.uuid());assertTrue(p.glorious());assertEquals(0,p.monthlyWins());assertEquals(1,p.awards().size());}
 @Test void tieGoesToFirstToReachTotal(){pay("a",alice,alice,Product.AVID,Kind.PURCHASE,2000,state.now-1000);avid("b",bob,2000);assertEquals(alice.uuid(),Ledger.rank(state,null).getFirst().person().uuid());}
 @Test void tieUsesFinalContributionTimeNotFirstContribution(){pay("a1",alice,alice,Product.AVID,Kind.PURCHASE,1000,state.now-2000);pay("b",bob,bob,Product.AVID,Kind.PURCHASE,2000,state.now-1000);avid("a2",alice,1000);assertEquals(bob.uuid(),Ledger.rank(state,null).getFirst().person().uuid());}
 @Test void equalTimestampTiesAreDeterministic(){avid("a",alice,2000);avid("b",bob,2000);UUID expected=Comparator.comparing(UUID::toString).compare(alice.uuid(),bob.uuid())<0?alice.uuid():bob.uuid();assertEquals(expected,Ledger.rank(state,null).getFirst().person().uuid());}
 @Test void historicalPaymentsDoNotEnterCurrentMonth(){pay("old",alice,alice,Product.AVID,Kind.PURCHASE,2000,Instant.parse("2026-08-15T12:00:00Z").toEpochMilli());assertEquals(2000,Ledger.view(state).profile(alice.uuid()).alltimeCents());assertEquals(0,Ledger.view(state).profile(alice.uuid()).monthlyCents());}
 @Test void monthBoundaryUsesConfiguredTimezone(){long boundary=YearMonth.of(2026,9).atDay(1).atStartOfDay(ZoneId.of(state.zone)).toInstant().toEpochMilli();pay("old",alice,alice,Product.AVID,Kind.PURCHASE,2000,boundary-1);pay("new",alice,alice,Product.AVID,Kind.PURCHASE,1000,boundary);assertEquals(1000,Ledger.view(state).profile(alice.uuid()).monthlyCents());}
 @Test void nextMonthStartsAtZero(){avid("p",alice,2000);Ledger.closeMonth(state);assertEquals(0,Ledger.view(state).profile(alice.uuid()).monthlyCents());assertEquals(2000,Ledger.view(state).profile(alice.uuid()).alltimeCents());}
 @Test void cancellationDoesNotRemovePaidAccess(){pay("s",alice,alice,Product.DEVOTEE,Kind.SUBSCRIPTION,500,state.now);Ledger.cancel(state,alice.uuid());Profile p=Ledger.view(state).profile(alice.uuid());assertTrue(p.devotee());assertTrue(p.renewalCancelled());}
 @Test void expiryPreservesSpending(){pay("s",alice,alice,Product.DEVOTEE,Kind.SUBSCRIPTION,500,state.now);Ledger.expire(state,alice.uuid());Profile p=Ledger.view(state).profile(alice.uuid());assertFalse(p.devotee());assertEquals(500,p.alltimeCents());}
 @Test void timeAdvanceExpiresWithoutRemovingAward(){pay("s",alice,alice,Product.DEVOTEE,Kind.SUBSCRIPTION,500,state.now);Ledger.manualGlorious(state,alice);Ledger.advanceTo(state,state.now+Duration.ofDays(31).toMillis());Profile p=Ledger.view(state).profile(alice.uuid());assertFalse(p.devotee());assertTrue(p.glorious());}
 @Test void renewalExtendsTerm(){pay("s",alice,alice,Product.DEVOTEE,Kind.SUBSCRIPTION,500,state.now);long first=state.payments.get("s").termEnd();pay("r",alice,alice,Product.DEVOTEE,Kind.RENEWAL,500,state.now);assertEquals(first+Duration.ofDays(30).toMillis(),state.payments.get("r").termEnd());assertEquals(1000,Ledger.view(state).profile(alice.uuid()).alltimeCents());}
 @Test void renewalRequiresPreviousDevotee(){assertThrows(IllegalArgumentException.class,()->pay("r",alice,alice,Product.DEVOTEE,Kind.RENEWAL,500,state.now));}
 @Test void avidCannotBeSubscription(){assertThrows(IllegalArgumentException.class,()->pay("r",alice,alice,Product.AVID,Kind.SUBSCRIPTION,500,state.now));}
 @Test void giftDevoteeBelongsToRecipient(){pay("g",alice,bob,Product.DEVOTEE,Kind.GIFT,500,state.now);assertTrue(Ledger.view(state).profile(bob.uuid()).devotee());assertFalse(Ledger.view(state).profile(alice.uuid()).devotee());}
 @Test void refundDoesNotEraseAnotherAvidGrant(){avid("a",alice,2000);avid("b",alice,2000);Ledger.reverse(state,"a",Status.REFUNDED);assertTrue(Ledger.view(state).profile(alice.uuid()).avid());assertEquals(2000,Ledger.view(state).profile(alice.uuid()).alltimeCents());}
 @Test void reversedGiftsRemainInHistoryButNotCompletedCounts(){pay("g",alice,bob,Product.AVID,Kind.GIFT,2000,state.now);Ledger.reverse(state,"g",Status.CHARGEBACK);View v=Ledger.view(state);assertEquals(0,v.profile(alice.uuid()).giftsSent());assertEquals(1,v.history(alice.uuid(),"sent").size());assertFalse(v.profile(bob.uuid()).avid());}
 @Test void usernameChangeUsesSameUuid(){avid("a",alice,2000);Person renamed=new Person(alice.uuid(),"AliceNew",true);avid("b",renamed,1000);Profile p=Ledger.view(state).profile(alice.uuid());assertEquals(3000,p.alltimeCents());assertEquals("AliceNew",p.person().name());}
 @Test void snapshotsAreImmutable(){avid("a",alice,2000);View v=Ledger.view(state);assertThrows(UnsupportedOperationException.class,()->v.people().clear());assertThrows(UnsupportedOperationException.class,()->v.payments().clear());assertThrows(UnsupportedOperationException.class,()->v.profile(alice.uuid()).awards().clear());}
 @Test void copyingStateDoesNotMutateOriginal(){State candidate=state.copy();Ledger.manualGlorious(candidate,alice);assertTrue(state.awards.isEmpty());}
 @Test void invalidStateFailsValidation(){state.openMonth="bad";assertThrows(RuntimeException.class,()->state.validate());}
 @Test void clockCannotGoBackwards(){assertThrows(IllegalArgumentException.class,()->Ledger.advanceTo(state,state.now-1));}
 @Test void emptyMonthsIssueNoAward(){assertTrue(Ledger.advanceTo(state,state.now+Duration.ofDays(100).toMillis()).isEmpty());}
 @Test void paidSumProperty(){Random r=new Random(42);long sum=0;for(int i=0;i<200;i++){long amount=r.nextInt(20000);avid("p"+i,alice,amount);if(i%3==0)Ledger.reverse(state,"p"+i,Status.REFUNDED);else sum+=amount;}assertEquals(sum,Ledger.view(state).profile(alice.uuid()).alltimeCents());}
 @Test void dstMonthUsesLocalCalendar(){state=Ledger.empty(Instant.parse("2026-03-20T12:00:00Z"),ZoneId.of("America/New_York"));avid("a",alice,2000);Ledger.closeMonth(state);assertEquals(Instant.parse("2026-04-01T04:00:00Z").toEpochMilli(),state.now);}
}
