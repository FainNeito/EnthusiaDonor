package com.enthusia.donors.entitlement;

import com.enthusia.donors.infrastructure.entitlement.ReconciliationFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReconciliationPersistenceTest {
    @TempDir Path directory;
    @Test void corruptedStateIsRejectedAndPreserved() throws Exception {
        var store = new ReconciliationFiles(directory, true);
        Files.writeString(directory.resolve("pending.json"), "corrupted");
        assertThrows(Exception.class, store::load);
        assertEquals("corrupted", Files.readString(directory.resolve("pending.json")));
    }
    @Test void journalFailurePreventsRankMutation() throws Exception {
        int[] changes = {0}; var id = UUID.randomUUID(); var start = Instant.parse("2026-10-15T00:00:00Z");
        var stored = new HashMap<UUID, Reconciliation.Pending>();
        var ranks = new Reconciliation.Ranks() {
            public List<Reconciliation.Candidate> candidates() { return List.of(new Reconciliation.Candidate(id, "Donor")); }
            public boolean eligible(Reconciliation.Candidate c) { return true; }
            public boolean remove(Reconciliation.Candidate c) { changes[0]++; return true; }
        };
        var store = new Reconciliation.Store() {
            public Map<UUID, Reconciliation.Pending> load() { return Map.copyOf(stored); }
            public void save(Map<UUID, Reconciliation.Pending> data) { stored.clear(); stored.putAll(data); }
            public void audit(Reconciliation.Candidate c, Instant now, String outcome) throws Exception { throw new java.io.IOException("disk full"); }
        };
        var e = new Reconciliation(ranks, c -> new Reconciliation.Evidence(Reconciliation.State.INACTIVE, "verified"), store,
                true, Duration.ofHours(1), Duration.ofHours(2));
        e.run(start); assertThrows(Exception.class, () -> e.run(start.plusSeconds(3600)));
        assertEquals(0, changes[0]);
    }
    @Test void stopDuringFinalVerificationPreventsMutation() throws Exception {
        int[] changes = {0}; var id = UUID.randomUUID(); var start = Instant.parse("2026-10-15T00:00:00Z");
        var ranks = new Reconciliation.Ranks() {
            public List<Reconciliation.Candidate> candidates() { return List.of(new Reconciliation.Candidate(id, "Donor")); }
            public boolean eligible(Reconciliation.Candidate c) { return true; }
            public boolean remove(Reconciliation.Candidate c) { changes[0]++; return true; }
        };
        Reconciliation[] holder = new Reconciliation[1]; int[] checks = {0};
        holder[0] = new Reconciliation(ranks, c -> {
            if (++checks[0] == 3) holder[0].stop();
            return new Reconciliation.Evidence(Reconciliation.State.INACTIVE, "verified");
        }, new ReconciliationFiles(directory, true), true, Duration.ofHours(1), Duration.ofHours(2));
        holder[0].run(start); holder[0].run(start.plusSeconds(3600)); assertEquals(0, changes[0]);
    }
}
