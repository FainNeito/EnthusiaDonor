package com.enthusia.donors.sandbox.placeholder;

import com.enthusia.donors.cache.DonorCache;
import com.enthusia.donors.cache.PlayerStatCache;
import com.enthusia.donors.model.DonorEntry;
import com.enthusia.donors.model.RefreshState;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OfficialPlaceholderValuesTest {
    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");
    private static final OfficialPlaceholderValues.DisplaySettings DISPLAY =
            new OfficialPlaceholderValues.DisplaySettings("$", true, "None", "$0.00", "-", 10, true);

    @Test void publicDonorValuesComeFromOfficialSnapshot() {
        UUID real = UUID.randomUUID();
        DonorCache official = new DonorCache();
        official.replace(List.of(new DonorEntry(real, "RealDonor", new BigDecimal("75.00"),
                new BigDecimal("25.00"), 1, 1, NOW.toEpochMilli())), NOW, RefreshState.OK);
        var stats = PlayerStatCache.Snapshot.empty();

        assertEquals("RealDonor", OfficialPlaceholderValues.resolve(null, "alltime_top_1_name", official, stats, DISPLAY));
        assertEquals("$25.00", OfficialPlaceholderValues.resolve(null, "monthly_top_1_amount", official, stats, DISPLAY));
        assertEquals("$75.00", OfficialPlaceholderValues.resolve(real, "player_alltime_amount", official, stats, DISPLAY));
        assertEquals("1", OfficialPlaceholderValues.resolve(null, "monthly_count", official, stats, DISPLAY));
        assertEquals("ok", OfficialPlaceholderValues.resolve(null, "status", official, stats, DISPLAY));
        assertEquals("None", OfficialPlaceholderValues.resolve(null, "alltime_top_2_name", official, stats, DISPLAY));
    }

    @Test void missingOrFailedOfficialSourceNeverUsesTestDonors() {
        DonorCache official = new DonorCache();
        var stats = PlayerStatCache.Snapshot.empty();
        assertEquals("None", OfficialPlaceholderValues.resolve(null, "alltime_top_1_name", official, stats, DISPLAY));
        official.markNotConfigured();
        assertEquals("tebex_not_configured", OfficialPlaceholderValues.resolve(null, "status", official, stats, DISPLAY));
        official.replace(List.of(new DonorEntry(UUID.randomUUID(), "VerifiedDonor", BigDecimal.TEN,
                BigDecimal.ONE, 1, 1, NOW.toEpochMilli())), NOW, RefreshState.OK);
        official.markFailure("Tebex refresh failed");
        assertEquals("VerifiedDonor", OfficialPlaceholderValues.resolve(null, "monthly_top_1_name", official, stats, DISPLAY));
        assertEquals("tebex_failed", OfficialPlaceholderValues.resolve(null, "status", official, stats, DISPLAY));
    }

    @Test void amountPrivacyCoversFormattedAndRawFields() {
        DonorCache official = new DonorCache();
        UUID real = UUID.randomUUID();
        official.replace(List.of(new DonorEntry(real, "PrivateDonor", new BigDecimal("50.00"),
                new BigDecimal("10.00"), 1, 1, NOW.toEpochMilli())), NOW, RefreshState.OK);
        var hidden = new OfficialPlaceholderValues.DisplaySettings("$", true, "None", "$0.00", "-", 10, false);
        var stats = PlayerStatCache.Snapshot.empty();
        assertEquals("Hidden", OfficialPlaceholderValues.resolve(null, "alltime_top_1_amount", official, stats, hidden));
        assertEquals("Hidden", OfficialPlaceholderValues.resolve(null, "alltime_top_1_amount_raw", official, stats, hidden));
        assertEquals("Hidden", OfficialPlaceholderValues.resolve(real, "player_monthly_amount_raw", official, stats, hidden));
    }
}
