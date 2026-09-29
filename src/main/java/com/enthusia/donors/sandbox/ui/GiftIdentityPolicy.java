package com.enthusia.donors.sandbox.ui;

import java.util.UUID;

/** Limits what a public gift-history viewer can learn about a private gift. */
record GiftIdentityPolicy(boolean showBuyer, boolean showRecipient, boolean linkOther) {
    static GiftIdentityPolicy forHistory(boolean privateGift, UUID buyer, UUID recipient,
                                         UUID viewer, String type) {
        if (!privateGift) return new GiftIdentityPolicy(true, true, true);
        boolean viewerIsBuyer = viewer.equals(buyer);
        boolean viewerIsRecipient = viewer.equals(recipient);
        return new GiftIdentityPolicy(viewerIsBuyer, viewerIsBuyer || viewerIsRecipient,
                viewerIsBuyer && type.equals("sent"));
    }
}
