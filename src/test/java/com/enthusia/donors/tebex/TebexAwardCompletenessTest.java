package com.enthusia.donors.tebex;

import org.junit.jupiter.api.Test;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class TebexAwardCompletenessTest {
    private final TebexClient client=new TebexClient(Logger.getAnonymousLogger());
    private static String page(String payment) {return "{\"data\":["+payment+"],\"current_page\":1,\"last_page\":1,\"total\":1,\"per_page\":25}";}
    private static final String COMPLETE="{\"id\":1,\"packages\":[{\"id\":123}],\"amount\":\"30.00\",\"status\":\"Complete\",\"date\":\"2026-10-01T00:00:00Z\",\"currency\":{\"iso_4217\":\"USD\"},\"player\":{\"uuid\":\"365bfa21803249ee9b634fe890c9d43f\",\"name\":\"Donor\"}}";
    @Test void missingOrMalformedPackageMetadataCannotBypassExclusions() {
        String packages="\"packages\":[{\"id\":123}],";
        for(String replacement:java.util.List.of("", "\"packages\":null,", "\"packages\":[],", "\"packages\":[{}],", "\"packages\":[\"bad\"],"))
            assertThrows(TebexClient.TebexException.class,()->client.parseAwardPayments(page(COMPLETE.replace(packages,replacement))));
    }
    @Test void validPageCanBeUsedForFurtherAwardVerification() {
        assertEquals(1,client.parseAwardPayments(page(COMPLETE)).payments().size());
    }
    @Test void missingIdentityCannotBeSilentlyIgnoredForAnAward() {
        String missing=COMPLETE.replace("\"uuid\":\"365bfa21803249ee9b634fe890c9d43f\",","");
        assertTrue(client.parsePayments(page(missing)).payments().isEmpty());
        assertThrows(TebexClient.TebexException.class,()->client.parseAwardPayments(page(missing)));
    }
    @Test void unknownStatusAndMissingCurrencyAreHeld() {
        assertThrows(TebexClient.TebexException.class,()->client.parseAwardPayments(page(COMPLETE.replace("Complete","Mystery"))));
        assertThrows(TebexClient.TebexException.class,()->client.parseAwardPayments(page(COMPLETE.replace("\"currency\":{\"iso_4217\":\"USD\"},",""))));
    }
    @Test void inconsistentPageCountsAreHeld() {
        assertThrows(TebexClient.TebexException.class,()->client.parseAwardPayments(page(COMPLETE).replace("\"total\":1","\"total\":2")));
        assertThrows(TebexClient.TebexException.class,()->client.parseAwardPayments(page(COMPLETE).replace(",\"total\":1","")));
    }
    @Test void stableScanEvidenceHasImmutableRecordsAndValidTimeOrder() {
        var now=java.time.Instant.parse("2026-11-01T00:00:00Z");
        var payments=new java.util.ArrayList<>(client.parseAwardPayments(page(COMPLETE)).payments());
        var scan=new StableAwardScan(now,now,payments,"test");payments.clear();
        assertEquals(1,scan.payments().size());
        assertThrows(UnsupportedOperationException.class,()->scan.payments().clear());
        assertTrue(scan.belongsTo("test"));assertFalse(scan.belongsTo("other"));
        assertThrows(IllegalArgumentException.class,()->new StableAwardScan(now,now.minusSeconds(1),java.util.List.of(),"test"));
        assertFalse(java.lang.reflect.Modifier.isPublic(StableAwardScan.class.getDeclaredConstructors()[0].getModifiers()));
    }
}
