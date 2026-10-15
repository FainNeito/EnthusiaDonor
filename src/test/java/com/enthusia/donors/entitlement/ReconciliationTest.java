package com.enthusia.donors.entitlement;

import com.enthusia.donors.infrastructure.entitlement.ReconciliationFiles;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReconciliationTest {
    @TempDir Path dir;
    final Instant start = Instant.parse("2026-10-15T00:00:00Z");
    final Reconciliation.Candidate player = new Reconciliation.Candidate(UUID.randomUUID(), "Donor");
    final FakeRanks ranks = new FakeRanks();
    Reconciliation.Evidence evidence = new Reconciliation.Evidence(Reconciliation.State.INACTIVE, "customer:payment");
    int checks;
    boolean failSource, renewOnFinal;

    Reconciliation engine(boolean enforce) throws Exception {
        return new Reconciliation(ranks, candidate -> {
            checks++;
            if (failSource) throw new IllegalStateException("unavailable");
            if (renewOnFinal && checks >= 3) return new Reconciliation.Evidence(Reconciliation.State.ACTIVE, "");
            return evidence;
        }, new ReconciliationFiles(dir, enforce), enforce, Duration.ofHours(1), Duration.ofHours(2));
    }
    class FakeRanks implements Reconciliation.Ranks {
        int removed;
        boolean eligible = true;
        boolean failSave;
        public List<Reconciliation.Candidate> candidates() { return List.of(player); }
        public boolean eligible(Reconciliation.Candidate candidate) { return eligible; }
        public boolean remove(Reconciliation.Candidate candidate) {
            if (failSave) throw new IllegalStateException("uncertain save");
            removed++; return true;
        }
    }
    @Test void firstAbsenceNeverRemoves() throws Exception {
        engine(true).run(start); assertEquals(0, ranks.removed);
    }
    @Test void repeatedAbsenceRequiresConfirmationWindow() throws Exception {
        var e = engine(true); e.run(start); e.run(start.plusSeconds(1800)); assertEquals(0, ranks.removed);
        e.run(start.plusSeconds(3600)); assertEquals(1, ranks.removed);
    }
    @Test void freshRecheckProtectsRenewal() throws Exception {
        var e = engine(true); e.run(start); renewOnFinal = true;
        e.run(start.plusSeconds(3600)); assertEquals(0, ranks.removed);
    }
    @Test void activeOrOverlappingEntitlementResetsObservation() throws Exception {
        var e = engine(true); e.run(start);
        evidence = new Reconciliation.Evidence(Reconciliation.State.ACTIVE, ""); e.run(start.plusSeconds(3600));
        evidence = new Reconciliation.Evidence(Reconciliation.State.INACTIVE, "customer:payment"); e.run(start.plusSeconds(7200));
        assertEquals(0, ranks.removed);
    }
    @Test void outageResetsObservation() throws Exception {
        var e = engine(true); e.run(start); failSource = true; e.run(start.plusSeconds(3600));
        failSource = false; e.run(start.plusSeconds(7200)); assertEquals(0, ranks.removed);
    }
    @Test void unknownIdentityOrHistoryNeverRemoves() throws Exception {
        evidence = new Reconciliation.Evidence(Reconciliation.State.UNKNOWN, "");
        var e = engine(true); e.run(start); e.run(start.plusSeconds(3600)); assertEquals(0, ranks.removed);
    }
    @Test void changedCustomerOrPaymentRestartsConfirmation() throws Exception {
        var e = engine(true); e.run(start);
        evidence = new Reconciliation.Evidence(Reconciliation.State.INACTIVE, "other-customer:payment");
        e.run(start.plusSeconds(3600)); assertEquals(0, ranks.removed);
    }
    @Test void protectedMembershipNeverCallsSourceOrRemoves() throws Exception {
        ranks.eligible = false; var e = engine(true); e.run(start); e.run(start.plusSeconds(3600));
        assertEquals(0, checks); assertEquals(0, ranks.removed);
    }
    @Test void auditModeNeverWritesRanks() throws Exception {
        var e = engine(false); e.run(start); e.run(start.plusSeconds(3600)); assertEquals(0, ranks.removed);
    }
    @Test void restartRetainsConfirmationEvidence() throws Exception {
        engine(true).run(start); engine(true).run(start.plusSeconds(3600)); assertEquals(1, ranks.removed);
    }
    @Test void switchingAuditToEnforcementRequiresNewConfirmation() throws Exception {
        engine(false).run(start); engine(true).run(start.plusSeconds(3600)); assertEquals(0, ranks.removed);
    }
    @Test void staleOrBackwardsObservationsCannotRemove() throws Exception {
        var e = engine(true); e.run(start); e.run(start.plusSeconds(10800)); assertEquals(0, ranks.removed);
        e.run(start.minusSeconds(60)); assertEquals(0, ranks.removed);
    }
    @Test void sameTimestampIsNotASeparateCheck() throws Exception {
        var e = engine(true); e.run(start); e.run(start); assertEquals(0, ranks.removed);
    }
    @Test void failedSaveIsJournalledAsUncertain() throws Exception {
        var e = engine(true); e.run(start); ranks.failSave = true; e.run(start.plusSeconds(3600));
        String audit = java.nio.file.Files.readString(dir.resolve("audit.jsonl"));
        assertTrue(audit.contains("INTENT")); assertTrue(audit.contains("UNCERTAIN"));
        assertEquals(0, ranks.removed);
    }
}
