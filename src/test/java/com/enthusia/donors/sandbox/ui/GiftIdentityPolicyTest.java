package com.enthusia.donors.sandbox.ui;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GiftIdentityPolicyTest {
    private final UUID buyer = UUID.randomUUID();
    private final UUID recipient = UUID.randomUUID();
    private final UUID outsider = UUID.randomUUID();

    @Test void outsiderCannotPairPrivateGiftParticipantsOrFollowProfileLink() {
        for (String type : new String[]{"sent", "received"}) {
            assertEquals(new GiftIdentityPolicy(false, false, false),
                    GiftIdentityPolicy.forHistory(true, buyer, recipient, outsider, type));
        }
    }

    @Test void recipientDoesNotLearnPrivateBuyerIdentity() {
        assertEquals(new GiftIdentityPolicy(false, true, false),
                GiftIdentityPolicy.forHistory(true, buyer, recipient, recipient, "received"));
    }

    @Test void buyerMayInspectOwnSentGift() {
        assertEquals(new GiftIdentityPolicy(true, true, true),
                GiftIdentityPolicy.forHistory(true, buyer, recipient, buyer, "sent"));
    }

    @Test void publicGiftsRetainExistingLinks() {
        assertEquals(new GiftIdentityPolicy(true, true, true),
                GiftIdentityPolicy.forHistory(false, buyer, recipient, outsider, "sent"));
    }
}
