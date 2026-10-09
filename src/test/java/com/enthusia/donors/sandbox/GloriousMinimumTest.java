package com.enthusia.donors.sandbox;

import com.enthusia.donors.sandbox.domain.*;
import com.enthusia.donors.sandbox.domain.Domain.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class GloriousMinimumTest {
    State state=Ledger.empty(Instant.parse("2026-09-20T12:00:00Z"),ZoneId.of("America/Chicago"));
    Person donor=Person.synthetic("Donor");
    void pay(String id,long cents) {Ledger.add(state,id,donor,donor,Product.AVID,Kind.PURCHASE,cents,state.now,30);}
    @Test void defaultRejectsOneCentBelowThirty() {pay("a",2999);assertTrue(Ledger.closeMonth(state).isEmpty());assertEquals("2026-10",state.openMonth);}
    @Test void exactlyThirtyQualifies() {pay("a",3000);assertEquals(3000,Ledger.closeMonth(state).getFirst().cents());}
    @Test void contributionsAreAggregated() {pay("a",2000);pay("b",1000);assertEquals(1,Ledger.closeMonth(state).size());}
    @Test void meetingMinimumDoesNotReplaceMonthlyWinnerRule() {
        pay("a",3000);Person other=Person.synthetic("Other");Ledger.add(state,"b",other,other,Product.AVID,Kind.PURCHASE,4000,state.now,30);
        var awards=Ledger.closeMonth(state);assertEquals(1,awards.size());assertEquals(other.uuid(),awards.getFirst().winner());
    }
    @Test void giftsQualifyThePayerOnly() {
        Person recipient=Person.synthetic("Recipient");Ledger.add(state,"gift",donor,recipient,Product.AVID,Kind.GIFT,3000,state.now,30);
        assertEquals(donor.uuid(),Ledger.closeMonth(state).getFirst().winner());assertFalse(Ledger.view(state).profile(recipient.uuid()).glorious());
    }
    @Test void chargebacksBeforeCloseDoNotQualify() {pay("a",3000);Ledger.reverse(state,"a",Status.CHARGEBACK);assertTrue(Ledger.closeMonth(state).isEmpty());}
    @Test void lifetimeSpendingCannotMakeUpMonthlyShortfall() {pay("old",10000);Ledger.closeMonth(state);pay("new",2999);assertTrue(Ledger.closeMonth(state).isEmpty());assertEquals(1,state.awards.size());}
    @Test void configuredHigherMinimumIsEnforced() {pay("a",4000);assertTrue(Ledger.closeMonth(state,5000).isEmpty());}
    @Test void configuredLowerMinimumIsEnforced() {pay("a",2000);assertEquals(1,Ledger.closeMonth(state,2000).size());}
    @Test void clockAdvanceUsesConfiguredMinimum() {pay("a",4000);assertTrue(Ledger.advanceTo(state,state.now+Duration.ofDays(40).toMillis(),5000).isEmpty());}
    @Test void refundsBeforeFinalizationReduceEligibility() {pay("a",2000);pay("b",1000);Ledger.reverse(state,"b",Status.REFUNDED);assertTrue(Ledger.closeMonth(state).isEmpty());}
    @Test void zeroMinimumStillRequiresPositiveDonation() {pay("a",0);assertTrue(Ledger.closeMonth(state,0).isEmpty());}
    @Test void invalidMinimumCannotMutateState() {pay("a",3000);long now=state.now;assertThrows(IllegalArgumentException.class,()->Ledger.closeMonth(state,-1));assertEquals(now,state.now);assertTrue(state.awards.isEmpty());}
    @Test void configDefaultsToThirty() {assertEquals(3000,GloriousPolicy.load(new YamlConfiguration()).minimumMonthlyCents());}
    @Test void configSupportsQuotedAndNumericAmounts() {
        YamlConfiguration c=new YamlConfiguration();c.set("glorious.minimum-monthly-donation","45.25");assertEquals(4525,GloriousPolicy.load(c).minimumMonthlyCents());
        c.set("glorious.minimum-monthly-donation",15);assertEquals(1500,GloriousPolicy.load(c).minimumMonthlyCents());
    }
    @Test void invalidConfigIsRejected() {
        for(String value:new String[]{"-1","1.001","NaN","","9223372036854775807"}) {
            YamlConfiguration c=new YamlConfiguration();c.set("glorious.minimum-monthly-donation",value);
            assertThrows(IllegalArgumentException.class,()->GloriousPolicy.load(c),value);
        }
    }
}
