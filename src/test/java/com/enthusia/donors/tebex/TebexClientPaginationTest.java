package com.enthusia.donors.tebex;

import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class TebexClientPaginationTest {
    private final TebexClient client = new TebexClient(Logger.getAnonymousLogger());

    @Test void followsPageCountsEvenWhenNextUrlIsAbsent() {
        var page = client.parsePayments("{\"data\":[{\"id\":1,\"amount\":\"5.00\",\"status\":\"Complete\",\"date\":\"2026-09-01T00:00:00Z\",\"player\":{\"uuid\":\"365bfa21803249ee9b634fe890c9d43f\",\"name\":\"Donor\"}}],\"current_page\":1,\"last_page\":2}");
        assertTrue(page.hasNextPage());
        assertEquals(1, page.payments().size());
    }

    @Test void rejectsIncompleteOrUnexpectedPaginatedResponses() {
        assertThrows(TebexClient.TebexException.class, () -> client.parsePayments("[]"));
        assertThrows(TebexClient.TebexException.class, () -> client.parsePayments("{\"data\":[],\"current_page\":1}"));
        assertThrows(TebexClient.TebexException.class, () -> client.parsePayments("{\"data\":[],\"current_page\":1,\"last_page\":2}"));
    }
}
