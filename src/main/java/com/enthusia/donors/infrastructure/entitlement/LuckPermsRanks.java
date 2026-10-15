package com.enthusia.donors.infrastructure.entitlement;

import com.enthusia.donors.entitlement.Reconciliation.*;
import com.enthusia.donors.entitlement.Reconciliation;
import net.luckperms.api.*;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.*;
import net.luckperms.api.node.matcher.NodeMatcher;
import net.luckperms.api.model.data.DataMutateResult;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Optional LuckPerms adapter; never link this class when LuckPerms is absent. */
public final class LuckPermsRanks implements Reconciliation.Ranks {
    private final LuckPerms api;
    private final ReconciliationSettings settings;
    private final BooleanSupplier stopped;
    public LuckPermsRanks(ReconciliationSettings settings, BooleanSupplier stopped) {
        this(LuckPermsProvider.get(), settings, stopped);
    }
    LuckPermsRanks(LuckPerms api, ReconciliationSettings settings, BooleanSupplier stopped) {
        this.api = api; this.settings = settings; this.stopped = stopped;
    }
    private static <T> T await(CompletableFuture<T> future) throws Exception { return future.get(30, TimeUnit.SECONDS); }
    public List<Candidate> candidates() throws Exception {
        await(api.getGroupManager().loadAllGroups());
        var nodes = await(api.getUserManager().searchAll(NodeMatcher.key("group.devotee")));
        if (nodes.size() > settings.maxUsers()) throw new IllegalStateException("Reconciliation user bound exceeded; no ranks changed");
        var candidates = new ArrayList<Candidate>();
        for (UUID uuid : nodes.keySet().stream().sorted().toList()) {
            if (stopped.getAsBoolean()) break;
            User user = await(api.getUserManager().loadUser(uuid));
            String name = user.getUsername();
            if (name != null && !name.isBlank()) candidates.add(new Candidate(uuid, name, user.getPrimaryGroup()));
        }
        return List.copyOf(candidates);
    }
    public boolean eligible(Candidate candidate) throws Exception {
        return eligibleNode(await(api.getUserManager().loadUser(candidate.uuid())), candidate) != null;
    }
    private Node eligibleNode(User user, Candidate candidate) {
        if (stopped.getAsBoolean() || settings.protectedUsers().contains(candidate.uuid())
                || !candidate.name().equalsIgnoreCase(user.getUsername())
                || (!candidate.primaryGroup().isBlank() && !candidate.primaryGroup().equals(user.getPrimaryGroup()))) return null;
        var direct = user.data().toCollection().stream().filter(n -> n.getKey().equals("group.devotee")).toList();
        // Do not mix direct permanent access with contextual, negative or temporary grants.
        if (direct.size() != 1 || !direct.getFirst().getValue() || direct.getFirst().hasExpiry()
                || !direct.getFirst().getContexts().isEmpty()) return null;
        for (var group : user.getInheritedGroups(api.getQueryOptionsRegistry().defaultNonContextualOptions())) {
            String name = group.getName().toLowerCase(Locale.ROOT);
            if (settings.protectedGroups().contains(name) || name.contains("founder")) return null;
        }
        return direct.getFirst();
    }
    public boolean remove(Candidate candidate) throws Exception {
        User user = await(api.getUserManager().loadUser(candidate.uuid()));
        Node target = eligibleNode(user, candidate);
        if (target == null) return false;
        Node rechecked = eligibleNode(user, candidate);
        if (rechecked == null || !rechecked.equals(target, NodeEqualityPredicate.EXACT)) return false;
        String fallback = null;
        if (user.getPrimaryGroup().equals("devotee")) {
            fallback = user.data().toCollection().stream()
                    .filter(n -> !n.getKey().equals("group.devotee") && n.getKey().startsWith("group.")
                            && n.getValue() && n.getContexts().isEmpty() && !n.hasExpiry())
                    .map(n -> api.getGroupManager().getGroup(n.getKey().substring(6)))
                    .filter(Objects::nonNull)
                    .max(Comparator.comparingInt(g -> g.getWeight().orElse(0)))
                    .map(net.luckperms.api.model.group.Group::getName).orElse("default");
            if (fallback.equals("default") && user.data().toCollection().stream()
                    .noneMatch(n -> n.getKey().equals("group.default") && n.getValue() && n.getContexts().isEmpty() && !n.hasExpiry())) {
                if (user.data().toCollection().stream().anyMatch(n -> n.getKey().equals("group.default")))
                    throw new IllegalStateException("Nonstandard default membership; Devotee retained");
                if (stopped.getAsBoolean()) return false;
                Node defaultParent = api.getNodeBuilderRegistry().forInheritance().group("default").build();
                DataMutateResult added = user.data().add(defaultParent);
                if (!added.wasSuccessful() && added != DataMutateResult.FAIL_ALREADY_HAS)
                    throw new IllegalStateException("Cannot establish default parent; Devotee retained");
            }
            if (stopped.getAsBoolean()) return false;
            // The API requires direct membership; FAIL_ALREADY_HAS is an already-correct stored fallback.
            DataMutateResult primary = user.setPrimaryGroup(fallback);
            if (!primary.wasSuccessful() && primary != DataMutateResult.FAIL_ALREADY_HAS)
                throw new IllegalStateException("Cannot establish primary group; Devotee retained");
        }
        if (stopped.getAsBoolean()) return false;
        var removed = new java.util.concurrent.atomic.AtomicBoolean();
        // Predicate is evaluated on the actual mutation candidates. Never select a replacement node.
        user.data().clear(node -> {
            boolean matches = !stopped.getAsBoolean() && node.getValue() && !node.hasExpiry() && node.getContexts().isEmpty()
                    && node.equals(target, NodeEqualityPredicate.EXACT);
            if (matches) removed.set(true);
            return matches;
        });
        if (!removed.get()) return false;
        await(api.getUserManager().saveUser(user));
        // Share the committed change with other LuckPerms backends/proxy.
        api.getMessagingService().ifPresent(service -> service.pushUserUpdate(user));
        return true;
    }
}
