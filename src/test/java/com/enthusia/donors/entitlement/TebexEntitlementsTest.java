package com.enthusia.donors.entitlement;

import com.enthusia.donors.infrastructure.entitlement.TebexEntitlements;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TebexEntitlementsTest {
    final UUID uuid = UUID.fromString("bf15f0ff-5c20-4476-918a-a525e28f4270");
    final Reconciliation.Candidate candidate = new Reconciliation.Candidate(uuid, "Donor");
    final Map<String, String> responses = new HashMap<>();
    final List<String> requested = new ArrayList<>();
    TebexEntitlementsTest() {
        responses.put("/information", "{\"server\":{\"id\":2147009}}");
        responses.put("/package/7053397", "{\"id\":7053397,\"type\":\"subscription\",\"disabled\":false}");
        responses.put("/users/profiles/minecraft/Donor", "{\"id\":\"bf15f0ff5c204476918aa525e28f4270\",\"name\":\"Donor\"}");
        responses.put("/user/Donor", """
                {"player":{"id":"offlinecustomer","username":"Donor"},
                 "payments":[{"txn_id":"tbx-paid","time":1600000000,"status":1}]}
                """);
        responses.put("/player/offlinecustomer/packages", "[]");
        responses.put("/payments/tbx-paid", """
                {"status":"Complete","amount":"10.92","gateway":{"name":"PayPal"},
                 "player":{"name":"Donor","uuid":"offlinecustomer"},"packages":[{"id":7053397}]}
                """);
    }
    TebexEntitlements client() {
        return new TebexEntitlements((path, tebex) -> {
            requested.add(path);
            if (!responses.containsKey(path)) throw new java.io.IOException("unavailable");
            return JsonParser.parseString(responses.get(path));
        }, Set.of(7053397L), 2147009, 100);
    }
    @Test void verifiedPaidHistoryAndExplicitNoActivePackagesQualify() throws Exception {
        var c = client(); c.validate(); assertEquals(Reconciliation.State.INACTIVE, c.check(candidate).state());
        assertTrue(requested.contains("/player/offlinecustomer/packages"));
        assertFalse(requested.stream().anyMatch(p -> p.contains(uuid.toString())));
    }
    @Test void anyActiveDevoteePurchaseProtectsIncludingOverlap() throws Exception {
        responses.put("/player/offlinecustomer/packages", "[{\"package\":{\"id\":123}},{\"package\":{\"id\":7053397}}]");
        assertEquals(Reconciliation.State.ACTIVE, client().check(candidate).state());
        assertFalse(requested.contains("/payments/tbx-paid"));
    }
    @Test void missingOrMalformedActiveResponseIsNeverEmpty() {
        for (String response : List.of("null", "{}", "{\"data\":[]}", "[{\"package\":{}}]", "[{\"package\":{\"id\":7053397.5}}]")) {
            responses.put("/player/offlinecustomer/packages", response);
            assertThrows(Exception.class, () -> client().check(candidate));
        }
    }
    @Test void missingPaidHistoryPreservesManualGrant() throws Exception {
        responses.put("/user/Donor", "{\"player\":{\"id\":\"offlinecustomer\",\"username\":\"Donor\"},\"payments\":[]}");
        assertEquals(Reconciliation.State.UNKNOWN, client().check(candidate).state());
    }
    @Test void declinedPaymentDoesNotEstablishPaidHistory() throws Exception {
        responses.put("/user/Donor", responses.get("/user/Donor").replace("\"status\":1", "\"status\":18"));
        assertEquals(Reconciliation.State.UNKNOWN, client().check(candidate).state());
    }
    @Test void manualAndFreePaymentsDoNotEstablishSubscriptionHistory() throws Exception {
        String paid = responses.get("/payments/tbx-paid");
        responses.put("/payments/tbx-paid", paid.replace("PayPal", "Manual"));
        assertEquals(Reconciliation.State.UNKNOWN, client().check(candidate).state());
        responses.put("/payments/tbx-paid", paid.replace("10.92", "0.00"));
        assertEquals(Reconciliation.State.UNKNOWN, client().check(candidate).state());
    }
    @Test void otherPackageDoesNotAuthorizeDevoteeRemoval() throws Exception {
        responses.put("/payments/tbx-paid", responses.get("/payments/tbx-paid").replace("7053397", "123"));
        assertEquals(Reconciliation.State.UNKNOWN, client().check(candidate).state());
    }
    @Test void refundedHistoryDoesNotAuthorizeRemoval() throws Exception {
        responses.put("/payments/tbx-paid", responses.get("/payments/tbx-paid").replace("Complete", "Refund"));
        assertEquals(Reconciliation.State.UNKNOWN, client().check(candidate).state());
    }
    @Test void mismatchedMojangIdentityOrTebexNamePreservesRank() throws Exception {
        String profile = responses.get("/users/profiles/minecraft/Donor");
        responses.put("/users/profiles/minecraft/Donor", profile.replace("bf15", "cf15"));
        assertEquals(Reconciliation.State.UNKNOWN, client().check(candidate).state());
        responses.put("/users/profiles/minecraft/Donor", profile);
        responses.put("/user/Donor", responses.get("/user/Donor").replace("\"Donor\"", "\"Other\""));
        assertEquals(Reconciliation.State.UNKNOWN, client().check(candidate).state());
    }
    @Test void giftedOrRenamedRecipientMismatchPreservesRank() throws Exception {
        responses.put("/payments/tbx-paid", responses.get("/payments/tbx-paid").replace("\"name\":\"Donor\"", "\"name\":\"Other\""));
        assertEquals(Reconciliation.State.UNKNOWN, client().check(candidate).state());
    }
    @Test void futurePaymentOrOversizedHistoryPreservesRank() throws Exception {
        responses.put("/user/Donor", responses.get("/user/Donor").replace("1600000000", "9999999999"));
        assertEquals(Reconciliation.State.UNKNOWN, client().check(candidate).state());
    }
    @Test void wrongServerOrNonSubscriptionPackageRejectsPass() {
        responses.put("/information", "{\"server\":{\"id\":123}}");
        assertThrows(Exception.class, () -> client().validate());
        responses.put("/information", "{\"server\":{\"id\":2147009}}");
        responses.put("/package/7053397", "{\"id\":7053397,\"type\":\"single\"}");
        assertThrows(Exception.class, () -> client().validate());
    }
    @Test void bedrockIdentityIsHeldForReview() throws Exception {
        assertEquals(Reconciliation.State.UNKNOWN, client().check(new Reconciliation.Candidate(uuid, "*Bedrock")).state());
        assertTrue(requested.isEmpty());
    }
    @Test void networkFailurePropagatesRatherThanAuthorizingRemoval() {
        responses.remove("/player/offlinecustomer/packages");
        assertThrows(Exception.class, () -> client().check(candidate));
    }
}
