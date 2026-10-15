package com.enthusia.donors.infrastructure.entitlement;

import com.enthusia.donors.entitlement.Reconciliation.*;
import com.enthusia.donors.entitlement.Reconciliation;
import net.luckperms.api.*;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.*;
import net.luckperms.api.node.matcher.NodeMatcher;
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
        return eligible(await(api.getUserManager().loadUser(candidate.uuid())), candidate);
    }
    private boolean eligible(User user, Candidate candidate) {
        if (stopped.getAsBoolean() || settings.protectedUsers().contains(candidate.uuid())
                || !candidate.name().equalsIgnoreCase(user.getUsername())
                || (!candidate.primaryGroup().isBlank() && !candidate.primaryGroup().equals(user.getPrimaryGroup()))) return false;
        var direct = user.data().toCollection().stream().filter(n -> n.getKey().equals("group.devotee")).toList();
        // Do not mix direct permanent access with contextual, negative or temporary grants.
        if (direct.size() != 1 || !direct.getFirst().getValue() || direct.getFirst().hasExpiry()
                || !direct.getFirst().getContexts().isEmpty()) return false;
        for (var group : user.getInheritedGroups(api.getQueryOptionsRegistry().defaultNonContextualOptions())) {
            String name = group.getName().toLowerCase(Locale.ROOT);
            if (settings.protectedGroups().contains(name) || name.contains("founder")) return false;
        }
        return true;
    }
    public boolean remove(Candidate candidate) throws Exception {
        User user = await(api.getUserManager().loadUser(candidate.uuid()));
        if (!eligible(user, candidate)) return false;
        var node = user.data().toCollection().stream().filter(n -> n.getKey().equals("group.devotee")).findFirst().orElseThrow();
        if (!user.data().contains(node, NodeEqualityPredicate.EXACT).asBoolean()) return false;
        if (stopped.getAsBoolean() || !user.data().remove(node).wasSuccessful()) return false;
        if (user.getPrimaryGroup().equals("devotee")) {
            String fallback = user.data().toCollection().stream()
                    .filter(n -> n.getKey().startsWith("group.") && n.getValue() && n.getContexts().isEmpty() && !n.hasExpiry())
                    .map(n -> api.getGroupManager().getGroup(n.getKey().substring(6)))
                    .filter(Objects::nonNull)
                    .max(Comparator.comparingInt(g -> g.getWeight().orElse(0)))
                    .map(net.luckperms.api.model.group.Group::getName).orElse("default");
            if (!user.setPrimaryGroup(fallback).wasSuccessful()) throw new IllegalStateException("Primary group update uncertain");
        }
        await(api.getUserManager().saveUser(user));
        // Share the committed change with other LuckPerms backends/proxy.
        api.getMessagingService().ifPresent(service -> service.pushUserUpdate(user));
        return true;
    }
}
