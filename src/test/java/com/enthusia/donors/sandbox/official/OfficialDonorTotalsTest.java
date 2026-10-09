package com.enthusia.donors.sandbox.official;

import com.enthusia.donors.config.DonorsConfig;
import com.enthusia.donors.model.DonorEntry;
import com.enthusia.donors.model.PaymentRecord;
import com.enthusia.donors.storage.DonorRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class OfficialDonorTotalsTest {
    @TempDir Path folder;
    private static final ZoneId ZONE = ZoneId.of("UTC");
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test void officialPaymentPolicyBuildsCurrentMonthAndAllTimeIndependently() throws Exception {
        DonorRepository repository = new DonorRepository(folder.resolve("official-only"), Logger.getAnonymousLogger());
        repository.init();
        Instant old = YearMonth.now(ZONE).minusMonths(1).atDay(2).atStartOfDay(ZONE).toInstant();
        Instant current = YearMonth.now(ZONE).atDay(2).atStartOfDay(ZONE).toInstant();
        repository.upsertPayments(List.of(
                payment("old", ALICE, "Alice", "10.00", "Complete", old, false, false),
                payment("new", ALICE, "Alice", "20.00", "Complete", current, false, false),
                payment("bob", BOB, "Bob", "5.00", "Complete", current, false, false),
                payment("refund", BOB, "Bob", "100.00", "Refund", current, true, false),
                payment("manual", BOB, "Bob", "200.00", "Complete", current, false, true)));

        List<DonorEntry> totals = repository.rebuildTotals(config(), Set.of());
        DonorEntry alice = totals.stream().filter(d -> d.uuid().equals(ALICE)).findFirst().orElseThrow();
        DonorEntry bob = totals.stream().filter(d -> d.uuid().equals(BOB)).findFirst().orElseThrow();
        assertEquals(new BigDecimal("30.00"), alice.alltimeTotal());
        assertEquals(new BigDecimal("20.00"), alice.monthlyTotal());
        assertEquals(1, alice.alltimeRank());
        assertEquals(1, alice.monthlyRank());
        assertEquals(new BigDecimal("5.00"), bob.alltimeTotal());
        assertEquals(new BigDecimal("5.00"), bob.monthlyTotal());
    }

    @Test void cachedPreviousMonthCannotAppearAsCurrentMonthLeader() {
        Instant previous = YearMonth.now(ZONE).minusMonths(1).atDay(2).atStartOfDay(ZONE).toInstant();
        DonorEntry old = new DonorEntry(ALICE, "Alice", BigDecimal.TEN, BigDecimal.TEN, 1, 1,
                previous.toEpochMilli());
        List<DonorEntry> adjusted = OfficialDonorReadService.forCurrentMonth(List.of(old), previous, config());
        assertEquals(BigDecimal.TEN, adjusted.getFirst().alltimeTotal());
        assertEquals(BigDecimal.ZERO, adjusted.getFirst().monthlyTotal());
        assertEquals(0, adjusted.getFirst().monthlyRank());
    }

    private static PaymentRecord payment(String id, UUID uuid, String name, String amount,
                                         String status, Instant date, boolean reversed, boolean manual) {
        return new PaymentRecord(id, uuid, name, new BigDecimal(amount), "USD", status, date,
                reversed, manual, List.of(1), Instant.now());
    }

    private static DonorsConfig config() {
        return new DonorsConfig("test-key", 10, 10, 3, 1000, "tebex_payments_only",
                false, false, false, false, Set.of(), Set.of(), Set.of(), Set.of(), ZONE,
                "$", true, 10, "None", "$0.00", "-", true, false, Path.of("unused.json"),
                false, "", "", "", "", "", "", "", false, true, false, 12);
    }
}
