package com.enthusia.donors.tebex;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class TebexClientDateTest {
    @Test void invalidPaymentDateCannotBecomeCurrentMonthSupport() throws Exception {
        TebexClient client = new TebexClient(Logger.getAnonymousLogger());
        JsonObject payment = new JsonObject();
        payment.addProperty("id", 42);
        payment.addProperty("amount", "20.00");
        payment.addProperty("status", "Complete");
        payment.addProperty("date", "not-a-date");
        JsonObject player = new JsonObject();
        player.addProperty("uuid", "365bfa21803249ee9b634fe890c9d43f");
        player.addProperty("name", "Donor");
        payment.add("player", player);

        assertThrows(TebexClient.TebexException.class,
                () -> client.parsePayment(payment, Instant.parse("2026-09-29T12:00:00Z")));
        payment.addProperty("date", "2026-08-03T12:00:00+00:00");
        assertEquals(Instant.parse("2026-08-03T12:00:00Z"),
                client.parsePayment(payment, Instant.parse("2026-09-29T12:00:00Z")).orElseThrow().createdAt());
    }
}
