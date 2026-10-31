package com.enthusia.donors.entitlement;

import java.time.*;
import java.util.*;

/** Official entitlement policy. No Paper, HTTP, database or LuckPerms dependencies. */
public final class Reconciliation {
    public enum State { ACTIVE, INACTIVE, UNKNOWN }
    public record Candidate(UUID uuid, String name, String primaryGroup, String targetGroup) {
        public Candidate {
            Objects.requireNonNull(uuid); Objects.requireNonNull(name); Objects.requireNonNull(primaryGroup);
            if (!Set.of("devotee", "devotee-founder").contains(targetGroup))
                throw new IllegalArgumentException("Unsupported subscription target");
        }
        public Candidate(UUID uuid, String name) { this(uuid, name, "", "devotee"); }
        public Candidate(UUID uuid, String name, String primaryGroup) { this(uuid, name, primaryGroup, "devotee"); }
    }
    public record Evidence(State state, String fingerprint) { }
    public record Pending(String fingerprint, Instant first, Instant last, int checks) { }
    public record Target(UUID uuid, String group) {
        public Target {
            Objects.requireNonNull(uuid);
            if (!Set.of("devotee", "devotee-founder").contains(group)) throw new IllegalArgumentException("Unsupported subscription target");
        }
        public Target(Candidate candidate) { this(candidate.uuid(), candidate.targetGroup()); }
    }
    public record Result(int candidates, int held, int waiting, int proposed, int removed, int uncertain) { }
    public interface Ranks {
        List<Candidate> candidates() throws Exception;
        boolean eligible(Candidate candidate) throws Exception;
        boolean remove(Candidate candidate) throws Exception;
    }
    @FunctionalInterface public interface Source { Evidence check(Candidate candidate) throws Exception; }
    public interface Store {
        Map<Target, Pending> load() throws Exception;
        void save(Map<Target, Pending> pending) throws Exception;
        void audit(Candidate candidate, Instant now, String outcome) throws Exception;
    }
    private final Ranks ranks;
    private final Source source;
    private final Store store;
    private final boolean enforce;
    private final Duration confirmation, maxGap;
    private volatile boolean stopped;

    public Reconciliation(Ranks ranks, Source source, Store store, boolean enforce, Duration confirmation, Duration maxGap) {
        if (confirmation.isNegative() || confirmation.isZero() || maxGap.isNegative() || maxGap.isZero())
            throw new IllegalArgumentException("Positive reconciliation durations required");
        this.ranks = ranks; this.source = source; this.store = store; this.enforce = enforce;
        this.confirmation = confirmation; this.maxGap = maxGap;
    }

    public void stop() { stopped = true; }

    public synchronized Result run(Instant now) throws Exception {
        var pending = new HashMap<>(store.load());
        var candidates = ranks.candidates();
        var present = new HashSet<Target>(); candidates.forEach(c -> present.add(new Target(c)));
        pending.keySet().retainAll(present);
        int held = 0, waiting = 0, proposed = 0, removed = 0, uncertain = 0;
        for (Candidate candidate : candidates) {
            if (stopped) break;
            Target target = new Target(candidate);
            Evidence evidence;
            try {
                if (!ranks.eligible(candidate)) {
                    pending.remove(target); held++; continue;
                }
                evidence = source.check(candidate);
            } catch (Exception unavailable) {
                pending.remove(target); held++;
                store.audit(candidate, now, "HELD_VERIFICATION_FAILED"); continue;
            }
            if (evidence.state() != State.INACTIVE || evidence.fingerprint().isBlank()) {
                pending.remove(target); held++;
                store.audit(candidate, now, "HELD_" + evidence.state()); continue;
            }
            // Never carry an earlier group's confirmation to a different direct membership.
            String scopedFingerprint = evidence.fingerprint() + "|target=" + candidate.targetGroup();
            Pending before = pending.get(target);
            boolean fresh = before != null && before.fingerprint().equals(scopedFingerprint)
                    && now.isAfter(before.last()) && !now.isBefore(before.first())
                    && Duration.between(before.last(), now).compareTo(maxGap) <= 0;
            Pending observation = fresh ? new Pending(before.fingerprint(), before.first(), now, Math.min(2, before.checks() + 1))
                    : new Pending(scopedFingerprint, now, now, 1);
            pending.put(target, observation);
            if (observation.checks() < 2 || Duration.between(observation.first(), now).compareTo(confirmation) < 0) {
                waiting++; continue;
            }
            if (!enforce) {
                proposed++; store.audit(candidate, now, "WOULD_REMOVE"); continue;
            }
            try {
                Evidence finalCheck = source.check(candidate);
                if (!finalCheck.equals(evidence) || !ranks.eligible(candidate) || stopped) {
                    pending.remove(target); held++; continue;
                }
            } catch (Exception unavailable) {
                pending.remove(target); held++; continue;
            }
            // Persist intent before touching permissions. Journal failure aborts the pass.
            store.audit(candidate, now, "INTENT");
            pending.remove(target);
            store.save(pending);
            if (stopped) break;
            boolean changed;
            try {
                changed = ranks.remove(candidate);
            } catch (Exception saveUncertain) {
                uncertain++; store.audit(candidate, now, "UNCERTAIN"); continue;
            }
            if (changed) removed++; else held++;
            store.audit(candidate, now, changed ? "REMOVED" : "HELD_MEMBERSHIP_CHANGED");
        }
        store.save(pending);
        return new Result(candidates.size(), held, waiting, proposed, removed, uncertain);
    }
}
