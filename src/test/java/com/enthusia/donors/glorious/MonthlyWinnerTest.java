package com.enthusia.donors.glorious;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.nio.file.Path;
import com.enthusia.donors.config.DonorsConfig;
import com.enthusia.donors.model.PaymentRecord;
import static org.junit.jupiter.api.Assertions.*;

class MonthlyWinnerTest {
    private static final YearMonth MONTH = YearMonth.of(2026, 10);
    private static final ZoneId ZONE = ZoneId.of("America/Chicago");
    private static final Instant CLOSED = MONTH.plusMonths(1).atDay(1).atStartOfDay(ZONE).toInstant();
    private static final UUID CUSTOMER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static MonthlyWinner.Payment payment(String id, String amount, String date) {
        return new MonthlyWinner.Payment(id, CUSTOMER, new BigDecimal(amount), "USD", Instant.parse(date), true);
    }
    @Test void sumsClosedMonthAndInclusiveThreshold() {
        var payments=List.of(payment("one","10.00","2026-10-03T00:00:00Z"),payment("two","20.00","2026-10-21T00:00:00Z"));
        var d=MonthlyWinner.decide(MONTH,ZONE,CLOSED,new BigDecimal("30.00"),payments,Map.of(CUSTOMER,PLAYER));
        assertEquals(MonthlyWinner.Status.WINNER,d.status()); assertEquals(PLAYER,d.playerId()); assertEquals(0,d.total().compareTo(new BigDecimal("30")));
    }
    @Test void beforeBoundaryOrUnverifiedWinnerIsHeld() {
        var p=List.of(payment("one","30","2026-10-03T00:00:00Z"));
        assertEquals(MonthlyWinner.Status.HOLD,MonthlyWinner.decide(MONTH,ZONE,CLOSED.minusMillis(1),new BigDecimal("30"),p,Map.of(CUSTOMER,PLAYER)).status());
        assertEquals(MonthlyWinner.Status.HOLD,MonthlyWinner.decide(MONTH,ZONE,CLOSED,new BigDecimal("30"),p,Map.of()).status());
    }
    @Test void previousAndNextMonthDoNotCount() {
        var p=List.of(payment("one","29.99","2026-10-03T00:00:00Z"),payment("two","100","2026-11-01T06:00:00Z"));
        assertEquals(MonthlyWinner.Status.NO_WINNER,MonthlyWinner.decide(MONTH,ZONE,CLOSED,new BigDecimal("30"),p,Map.of(CUSTOMER,PLAYER)).status());
    }
    @Test void duplicateAndOtherCurrencyFailClosed() {
        var p=payment("one","30","2026-10-03T00:00:00Z");
        assertEquals(MonthlyWinner.Status.HOLD,MonthlyWinner.decide(MONTH,ZONE,CLOSED,new BigDecimal("30"),List.of(p,p),Map.of(CUSTOMER,PLAYER)).status());
        var eur=new MonthlyWinner.Payment("two",CUSTOMER,new BigDecimal("40"),"EUR",p.paidAt(),true);
        assertEquals(MonthlyWinner.Status.HOLD,MonthlyWinner.decide(MONTH,ZONE,CLOSED,new BigDecimal("30"),List.of(eur),Map.of(CUSTOMER,PLAYER)).status());
    }
    @Test void verifiedFormerCustomerIdsAreCombinedForOnePlayer() {
        UUID former=UUID.fromString("00000000-0000-0000-0000-000000000003");
        var a=payment("one","15","2026-10-03T00:00:00Z");
        var b=new MonthlyWinner.Payment("two",former,new BigDecimal("15"),"USD",Instant.parse("2026-10-04T00:00:00Z"),true);
        var d=MonthlyWinner.decide(MONTH,ZONE,CLOSED,new BigDecimal("30"),List.of(a,b),Map.of(CUSTOMER,PLAYER,former,PLAYER));
        assertEquals(MonthlyWinner.Status.WINNER,d.status()); assertEquals(PLAYER,d.playerId());
    }
    @Test void unverifiedCompetitorCannotProduceWrongWinner() {
        UUID unknown=UUID.fromString("00000000-0000-0000-0000-000000000003");
        var a=payment("one","40","2026-10-03T00:00:00Z");
        var b=new MonthlyWinner.Payment("two",unknown,new BigDecimal("1"),"USD",Instant.parse("2026-10-04T00:00:00Z"),true);
        assertEquals(MonthlyWinner.Status.HOLD,MonthlyWinner.decide(MONTH,ZONE,CLOSED,new BigDecimal("30"),List.of(a,b),Map.of(CUSTOMER,PLAYER)).status());
    }
    @Test void activationExcludesPreviousMonthsButAllowsCurrentMonthAfterItEnds() {
        var boundary = new ActivationBoundary(Instant.parse("2026-10-10T12:00:00Z"), ZONE);
        assertFalse(boundary.mayFinalize(MONTH.minusMonths(1), CLOSED));
        assertFalse(boundary.mayFinalize(MONTH, CLOSED.minusMillis(1)));
        assertTrue(boundary.mayFinalize(MONTH, CLOSED));
    }
    @Test void uncertainOrInterruptedGrantCannotBeAutomaticallyClaimedAgain() {
        var claimed=AwardDecision.winner(MONTH,PLAYER).begin();
        assertThrows(IllegalStateException.class,claimed::begin);
        assertThrows(IllegalStateException.class,claimed.uncertain()::begin);
        assertThrows(IllegalStateException.class,claimed.confirmed()::begin);
        assertThrows(IllegalStateException.class,AwardDecision.noWinner(MONTH)::begin);
    }
    @Test void tiedTotalsChooseEarlierLastPaymentLikeSandbox() {
        UUID otherCustomer=UUID.fromString("00000000-0000-0000-0000-000000000003");
        UUID otherPlayer=UUID.fromString("00000000-0000-0000-0000-000000000004");
        var later=payment("one","30","2026-10-04T00:00:00Z");
        var earlier=new MonthlyWinner.Payment("two",otherCustomer,new BigDecimal("30"),"USD",Instant.parse("2026-10-03T00:00:00Z"),true);
        var d=MonthlyWinner.decide(MONTH,ZONE,CLOSED,new BigDecimal("30"),List.of(later,earlier),Map.of(CUSTOMER,PLAYER,otherCustomer,otherPlayer));
        assertEquals(otherPlayer,d.playerId());
    }
    @Test void awardInputsExcludeRefundsAndManualPaymentsEvenWhenDisplaysAllowThem() {
        var config=new DonorsConfig("test",10,10,3,1000,"tebex_payments_only",false,true,true,true,
                Set.of(),Set.of(),Set.of(),Set.of(),ZONE,"$",true,10,"None","$0","-",true,false,
                Path.of("unused"),false,"","","","","","","",false,true,false,12);
        Instant date=Instant.parse("2026-10-03T00:00:00Z");
        var good=new PaymentRecord("good",CUSTOMER,"Player",new BigDecimal("30"),"USD","Complete",date,false,false,List.of(1),date);
        var refund=new PaymentRecord("refund",CUSTOMER,"Player",new BigDecimal("30"),"USD","Refund",date,true,false,List.of(1),date);
        var manual=new PaymentRecord("manual",CUSTOMER,"Player",new BigDecimal("30"),"USD","Complete",date,false,true,List.of(1),date);
        var result=OfficialAwardPayments.from(List.of(good,refund,manual),config,Set.of(),Map.of());
        assertEquals(1,result.size()); assertEquals("good",result.getFirst().id());
        assertFalse(result.getFirst().payerVerified());
        assertEquals(MonthlyWinner.Status.HOLD,MonthlyWinner.decide(MONTH,ZONE,CLOSED,new BigDecimal("30"),result,Map.of(CUSTOMER,PLAYER)).status());
        var verified=OfficialAwardPayments.from(List.of(good,refund,manual),config,Set.of(),Map.of("good",CUSTOMER));
        assertTrue(verified.getFirst().payerVerified());
        assertEquals(MonthlyWinner.Status.WINNER,MonthlyWinner.decide(MONTH,ZONE,CLOSED,new BigDecimal("30"),verified,Map.of(CUSTOMER,PLAYER)).status());
    }
    @Test void privateReviewQueueUsesEligibleMonthAndVerifiedPayerInsteadOfRecipient() throws Exception {
        var config=new DonorsConfig("test",10,10,3,1000,"tebex_payments_only",false,true,true,true,
                Set.of(),Set.of(),Set.of(),Set.of(),ZONE,"$",true,10,"None","$0","-",true,false,
                Path.of("unused"),false,"","","","","","","",false,true,false,12);
        Instant date=Instant.parse("2026-10-03T00:00:00Z");
        var gift=new PaymentRecord("gift",CUSTOMER,"Recipient",new BigDecimal("30"),"USD","Complete",date,false,false,List.of(1),date);
        var refund=new PaymentRecord("refund",CUSTOMER,"Recipient",new BigDecimal("4"),"USD","Refund",date,true,false,List.of(1),date);
        var next=new PaymentRecord("next",CUSTOMER,"Recipient",new BigDecimal("5"),"USD","Complete",CLOSED,false,false,List.of(1),date);
        var pending=ReviewableAwardInput.prepare(MONTH,ZONE,CLOSED,CLOSED,true,List.of(gift,refund,next),config,Set.of(),Map.of());
        assertEquals(Set.of("gift"),pending.unverifiedPayments());
        assertEquals(MonthlyWinner.Status.HOLD,MonthlyWinner.decide(MONTH,ZONE,CLOSED,new BigDecimal("30"),pending.reconciliation().payments(),pending.reconciliation().verifiedCustomers()).status());
        var verified=ReviewableAwardInput.prepare(MONTH,ZONE,CLOSED,CLOSED,true,List.of(gift,refund,next),config,Set.of(),Map.of("gift",PLAYER));
        assertTrue(verified.unverifiedPayments().isEmpty());
        assertEquals(PLAYER,MonthlyWinner.decide(MONTH,ZONE,CLOSED,new BigDecimal("30"),verified.reconciliation().payments(),verified.reconciliation().verifiedCustomers()).playerId());
    }
    @Test void unsettledPaymentHoldsTheMonthEvenWhenNoCompletedPaymentsQualify() {
        var config=new DonorsConfig("test",10,10,3,1000,"tebex_payments_only",false,true,true,true,
                Set.of(),Set.of(),Set.of(),Set.of(),ZONE,"$",true,10,"None","$0","-",true,false,
                Path.of("unused"),false,"","","","","","","",false,true,false,12);
        Instant date=Instant.parse("2026-10-03T00:00:00Z");
        var pending=new PaymentRecord("pending",CUSTOMER,"Player",new BigDecimal("30"),"USD","Pending",date,false,false,List.of(1),date);
        var input=ReviewableAwardInput.prepare(MONTH,ZONE,CLOSED,CLOSED,true,List.of(pending),config,Set.of(),Map.of());
        assertFalse(input.reconciliation().verifiedComplete());assertTrue(input.reconciliation().payments().isEmpty());
        var outside=new PaymentRecord("next",CUSTOMER,"Player",new BigDecimal("30"),"USD","Pending",CLOSED,false,false,List.of(1),date);
        assertTrue(ReviewableAwardInput.prepare(MONTH,ZONE,CLOSED,CLOSED,true,List.of(outside),config,Set.of(),Map.of()).reconciliation().verifiedComplete());
    }
}
