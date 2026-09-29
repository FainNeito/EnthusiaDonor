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
            assertFalse(GiftIdentityPolicy.visibleToViewer(true, buyer, recipient, outsider));
            assertEquals(new GiftIdentityPolicy(false, false, false),
                    GiftIdentityPolicy.forHistory(true, buyer, recipient, outsider, type));
        }
    }

    @Test void recipientDoesNotLearnPrivateBuyerIdentity() {
        assertTrue(GiftIdentityPolicy.visibleToViewer(true, buyer, recipient, recipient));
        assertEquals(new GiftIdentityPolicy(false, true, false),
                GiftIdentityPolicy.forHistory(true, buyer, recipient, recipient, "received"));
    }

    @Test void buyerMayInspectOwnSentGift() {
        assertTrue(GiftIdentityPolicy.visibleToViewer(true, buyer, recipient, buyer));
        assertEquals(new GiftIdentityPolicy(true, true, true),
                GiftIdentityPolicy.forHistory(true, buyer, recipient, buyer, "sent"));
    }

    @Test void publicGiftsRetainExistingLinks() {
        assertTrue(GiftIdentityPolicy.visibleToViewer(false, buyer, recipient, outsider));
        assertEquals(new GiftIdentityPolicy(true, true, true),
                GiftIdentityPolicy.forHistory(false, buyer, recipient, outsider, "sent"));
    }
}
