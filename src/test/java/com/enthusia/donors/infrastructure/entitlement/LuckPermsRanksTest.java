package com.enthusia.donors.infrastructure.entitlement;

import com.enthusia.donors.entitlement.Reconciliation.Candidate;
import net.luckperms.api.*;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.model.data.*;
import net.luckperms.api.model.group.*;
import net.luckperms.api.model.user.*;
import net.luckperms.api.node.*;
import net.luckperms.api.node.matcher.NodeMatcher;
import net.luckperms.api.query.QueryOptionsRegistry;
import net.luckperms.api.util.Tristate;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import net.luckperms.api.node.types.InheritanceNode;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LuckPermsRanksTest {
    final UUID uuid = UUID.randomUUID();
    final Candidate candidate = new Candidate(uuid, "Donor");
    final LuckPerms api = mock(LuckPerms.class);
    final UserManager manager = mock(UserManager.class);
    final GroupManager groups = mock(GroupManager.class);
    final User user = mock(User.class);
    final NodeMap data = mock(NodeMap.class);
    final List<Node> nodes = new ArrayList<>();
    final List<Group> inherited = new ArrayList<>();
    final Node devotee = node("devotee");
    boolean stopped;
    LuckPermsRanks ranks;

    Node node(String group) {
        Node node = mock(InheritanceNode.class); when(node.getKey()).thenReturn("group." + group); when(node.getValue()).thenReturn(true);
        var context = mock(ImmutableContextSet.class); when(context.isEmpty()).thenReturn(true); when(node.getContexts()).thenReturn(context);
        when(node.equals(any(Node.class), eq(NodeEqualityPredicate.EXACT))).thenAnswer(call -> call.getArgument(0) == node);
        return node;
    }
    @BeforeEach void setup() {
        var config = new YamlConfiguration(); config.set("entitlements.enabled", true);
        config.set("entitlements.delivery-server-id", 2147009); config.set("entitlements.devotee-package-ids", List.of(7053397));
        config.set("tebex.api-key", "private-test-key");
        when(api.getUserManager()).thenReturn(manager); when(api.getGroupManager()).thenReturn(groups);
        when(api.getQueryOptionsRegistry()).thenReturn(mock(QueryOptionsRegistry.class));
        when(api.getMessagingService()).thenReturn(Optional.empty());
        when(manager.loadUser(uuid)).thenReturn(CompletableFuture.completedFuture(user));
        when(manager.saveUser(user)).thenReturn(CompletableFuture.completedFuture(null));
        when(user.data()).thenReturn(data); when(user.getUsername()).thenReturn("Donor"); when(user.getPrimaryGroup()).thenReturn("default");
        when(data.toCollection()).thenReturn(nodes); when(user.getInheritedGroups(any())).thenReturn(inherited);
        when(data.contains(any(), eq(NodeEqualityPredicate.EXACT))).thenReturn(Tristate.TRUE);
        when(data.remove(any())).thenAnswer(invocation -> { nodes.remove(invocation.getArgument(0)); return DataMutateResult.SUCCESS; });
        doAnswer(invocation -> { nodes.removeIf(invocation.getArgument(0)); return null; })
                .when(data).clear(org.mockito.ArgumentMatchers.<Predicate<? super Node>>any());
        Node defaultParent = node("default");
        var registry = mock(NodeBuilderRegistry.class); var builder = mock(InheritanceNode.Builder.class);
        when(api.getNodeBuilderRegistry()).thenReturn(registry); when(registry.forInheritance()).thenReturn(builder);
        when(builder.group("default")).thenReturn(builder); when(builder.build()).thenReturn((InheritanceNode) defaultParent);
        when(data.add(any())).thenAnswer(invocation -> {
            Node added = invocation.getArgument(0); nodes.add(added); return DataMutateResult.SUCCESS;
        });
        nodes.add(devotee);
        ranks = new LuckPermsRanks(api, ReconciliationSettings.load(config), () -> stopped);
    }
    @Test void removesOnlyTheDirectDevoteeNodeAndSavesOfflineUser() throws Exception {
        Node avid = node("avid"); nodes.add(avid);
        assertTrue(ranks.remove(candidate)); assertEquals(List.of(avid), nodes);
        verify(data).clear(org.mockito.ArgumentMatchers.<Predicate<? super Node>>any()); verify(manager).saveUser(user);
        verify(user, never()).setPrimaryGroup(any());
    }
    @Test void inheritedFounderMediaAndGloriousAreProtected() throws Exception {
        for (String name : List.of("founder", "media", "glorious", "custom-founder")) {
            var group = mock(Group.class); when(group.getName()).thenReturn(name);
            inherited.clear(); inherited.add(group); assertFalse(ranks.eligible(candidate)); assertFalse(ranks.remove(candidate));
        }
        verify(manager, never()).saveUser(any());
    }
    @Test void contextualTemporaryAndNegativeGrantsArePreserved() throws Exception {
        when(devotee.getContexts().isEmpty()).thenReturn(false); assertFalse(ranks.remove(candidate));
        when(devotee.getContexts().isEmpty()).thenReturn(true); when(devotee.hasExpiry()).thenReturn(true); assertFalse(ranks.remove(candidate));
        when(devotee.hasExpiry()).thenReturn(false); when(devotee.getValue()).thenReturn(false); assertFalse(ranks.remove(candidate));
        verify(manager, never()).saveUser(any());
    }
    @Test void changedNameMembershipOrPrimaryGroupIsHeld() throws Exception {
        assertFalse(ranks.eligible(new Candidate(uuid, "Other")));
        assertFalse(ranks.eligible(new Candidate(uuid, "Donor", "avid")));
        nodes.clear(); assertFalse(ranks.remove(candidate)); verify(manager, never()).saveUser(any());
    }
    @Test void shutdownPreventsMutations() throws Exception {
        stopped = true; assertFalse(ranks.remove(candidate)); verify(data, never()).remove(any());
    }
    @Test void saveFailureIsNotClaimedAsSuccessfulAndDoesNotBlindlyRestore() {
        when(manager.saveUser(user)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("unavailable")));
        assertThrows(Exception.class, () -> ranks.remove(candidate)); verify(data, never()).add(any());
    }
    @Test void committedSavePublishesNetworkInvalidation() throws Exception {
        var messaging = mock(net.luckperms.api.messaging.MessagingService.class);
        when(api.getMessagingService()).thenReturn(Optional.of(messaging));
        assertTrue(ranks.remove(candidate));
        var ordered = inOrder(manager, messaging); ordered.verify(manager).saveUser(user); ordered.verify(messaging).pushUserUpdate(user);
    }
    @Test void removedPrimaryDevoteeFallsBackToRemainingAvid() throws Exception {
        nodes.add(node("avid")); var avid = mock(Group.class);
        when(avid.getName()).thenReturn("avid"); when(avid.getWeight()).thenReturn(OptionalInt.of(10));
        when(groups.getGroup("avid")).thenReturn(avid); when(user.getPrimaryGroup()).thenReturn("devotee");
        when(user.setPrimaryGroup("avid")).thenReturn(DataMutateResult.SUCCESS);
        assertTrue(ranks.remove(candidate)); verify(user).setPrimaryGroup("avid");
    }
    @Test void reviewConcurrentTemporaryReplacementMustNotBeRemoved() throws Exception {
        Node replacement = node("devotee"); when(replacement.hasExpiry()).thenReturn(true);
        // The permanent node is validated, then a renewal/admin replaces it before selection.
        when(data.toCollection()).thenReturn(List.of(devotee), List.of(replacement));
        assertFalse(ranks.remove(candidate), "A newly replaced temporary membership must be retained");
        verify(data, never()).remove(replacement);
    }
    @Test void reviewSoleDevoteeParentMustStillBePersistentlyRemoved() throws Exception {
        when(user.getPrimaryGroup()).thenReturn("devotee");
        // Actual LuckPerms API rejects setPrimaryGroup(default) without a direct default node.
        when(user.setPrimaryGroup("default")).thenAnswer(call -> nodes.stream().anyMatch(n -> n.getKey().equals("group.default"))
                ? DataMutateResult.SUCCESS : DataMutateResult.FAIL);
        assertTrue(ranks.remove(candidate), "A sole direct Devotee parent must be removable without an unsaved mutation");
        verify(manager).saveUser(user);
    }
    @Test void replacementAtActualMutationMustSurvive() throws Exception {
        Node replacement = node("devotee"); when(replacement.hasExpiry()).thenReturn(true);
        doAnswer(invocation -> {
            nodes.clear(); nodes.add(replacement);
            nodes.removeIf(invocation.getArgument(0)); return null;
        }).when(data).clear(org.mockito.ArgumentMatchers.<Predicate<? super Node>>any());
        assertFalse(ranks.remove(candidate)); assertEquals(List.of(replacement), nodes);
        verify(manager, never()).saveUser(any());
    }
    @Test void alreadyCorrectStoredFallbackDoesNotBlockSave() throws Exception {
        nodes.add(node("avid")); var avid = mock(Group.class);
        when(avid.getName()).thenReturn("avid"); when(avid.getWeight()).thenReturn(OptionalInt.of(10));
        when(groups.getGroup("avid")).thenReturn(avid); when(user.getPrimaryGroup()).thenReturn("devotee");
        when(user.setPrimaryGroup("avid")).thenReturn(DataMutateResult.FAIL_ALREADY_HAS);
        assertTrue(ranks.remove(candidate)); verify(manager).saveUser(user);
    }
    @Test void rejectedFallbackMustRetainDevotee() throws Exception {
        when(user.getPrimaryGroup()).thenReturn("devotee");
        when(user.setPrimaryGroup("default")).thenReturn(DataMutateResult.FAIL);
        assertThrows(Exception.class, () -> ranks.remove(candidate)); assertTrue(nodes.contains(devotee));
        verify(data, never()).clear(org.mockito.ArgumentMatchers.<Predicate<? super Node>>any());
        verify(manager, never()).saveUser(any());
    }
    @Test void deniedDefaultMustNotBeOverwrittenToPrepareFallback() throws Exception {
        Node denied = node("default"); when(denied.getValue()).thenReturn(false); nodes.add(denied);
        when(user.getPrimaryGroup()).thenReturn("devotee");
        assertThrows(Exception.class, () -> ranks.remove(candidate));
        assertTrue(nodes.contains(devotee)); verify(data, never()).add(any());
        verify(data, never()).clear(org.mockito.ArgumentMatchers.<Predicate<? super Node>>any());
    }

    private LuckPermsRanks founderEnabled() {
        var config = new YamlConfiguration(); config.set("entitlements.enabled", true);
        config.set("entitlements.delivery-server-id", 2147009); config.set("entitlements.devotee-package-ids", List.of(7053397));
        config.set("entitlements.reconcile-founder-variant", true);
        config.set("entitlements.allow-direct-removal-from-protected-groups", true);
        config.set("tebex.api-key", "private-test-key");
        return new LuckPermsRanks(api, ReconciliationSettings.load(config), () -> stopped);
    }
    @Test void founderVariantRemovalKeepsFounderAndAvidMemberships() throws Exception {
        nodes.clear(); Node founderDevotee=node("devotee-founder"), avidFounder=node("avid-founder"), founder=node("founder");
        nodes.addAll(List.of(founderDevotee,avidFounder,founder));
        var inheritedFounder=mock(Group.class);when(inheritedFounder.getName()).thenReturn("founder");inherited.add(inheritedFounder);
        var target=new Candidate(uuid,"Donor","default","devotee-founder");
        var adapter=founderEnabled();assertTrue(adapter.eligible(target));assertTrue(adapter.remove(target));
        assertEquals(List.of(avidFounder,founder),nodes);verify(manager).saveUser(user);
    }
    @Test void discoversOfflineFounderOnlyCandidateWhenExplicitlyEnabled() throws Exception {
        nodes.clear();Node founderDevotee=node("devotee-founder");nodes.add(founderDevotee);
        when(groups.loadAllGroups()).thenReturn(CompletableFuture.completedFuture(null));
        Map<UUID,Collection<Node>> found=Map.of(uuid,List.of(founderDevotee));
        when(manager.<Node>searchAll(any())).thenReturn(CompletableFuture.completedFuture(Map.of()),CompletableFuture.completedFuture(found));
        try (var matchers = mockStatic(NodeMatcher.class)) {
            matchers.when(() -> NodeMatcher.key(anyString())).thenReturn(mock(NodeMatcher.class));
            assertEquals(List.of(new Candidate(uuid,"Donor","default","devotee-founder")),founderEnabled().candidates());
        }
    }
    @Test void discoversEligibleFounderVariantWhenOtherVariantIsTemporary() throws Exception {
        when(devotee.hasExpiry()).thenReturn(true);
        Node founderDevotee = node("devotee-founder"); nodes.add(founderDevotee);
        when(groups.loadAllGroups()).thenReturn(CompletableFuture.completedFuture(null));
        Map<UUID, Collection<Node>> first = Map.of(uuid, List.of(devotee));
        Map<UUID, Collection<Node>> second = Map.of(uuid, List.of(founderDevotee));
        when(manager.<Node>searchAll(any())).thenReturn(CompletableFuture.completedFuture(first), CompletableFuture.completedFuture(second));
        try (var matchers = mockStatic(NodeMatcher.class)) {
            matchers.when(() -> NodeMatcher.key(anyString())).thenReturn(mock(NodeMatcher.class));
            var adapter = founderEnabled();
            var normal = new Candidate(uuid, "Donor", "default", "devotee");
            var founder = new Candidate(uuid, "Donor", "default", "devotee-founder");
            assertEquals(List.of(normal, founder), adapter.candidates());
            assertFalse(adapter.eligible(normal));
            assertTrue(adapter.eligible(founder));
        }
    }
    @Test void combinedVariantDiscoveryAppliesUserLimitBeforeLoadingUsers() throws Exception {
        var config = new YamlConfiguration(); config.set("entitlements.enabled", true);
        config.set("entitlements.delivery-server-id", 2147009); config.set("entitlements.devotee-package-ids", List.of(7053397));
        config.set("entitlements.reconcile-founder-variant", true); config.set("entitlements.max-users", 1);
        config.set("tebex.api-key", "private-test-key");
        when(groups.loadAllGroups()).thenReturn(CompletableFuture.completedFuture(null));
        Map<UUID, Collection<Node>> first = Map.of(uuid, List.of(devotee));
        Map<UUID, Collection<Node>> second = Map.of(UUID.randomUUID(), List.of(node("devotee-founder")));
        when(manager.<Node>searchAll(any())).thenReturn(CompletableFuture.completedFuture(first), CompletableFuture.completedFuture(second));
        var adapter = new LuckPermsRanks(api, ReconciliationSettings.load(config), () -> stopped);
        try (var matchers = mockStatic(NodeMatcher.class)) {
            matchers.when(() -> NodeMatcher.key(anyString())).thenReturn(mock(NodeMatcher.class));
            var error = assertThrows(IllegalStateException.class, adapter::candidates);
            assertTrue(error.getMessage().contains("user bound exceeded"));
        }
        verify(manager, never()).loadUser(any(UUID.class));
    }
    @Test void directExpiredSubscriptionCanBeRemovedWithoutRemovingMediaOrGlorious() throws Exception {
        Node media=node("media"),glorious=node("glorious-founders");nodes.add(media);nodes.add(glorious);
        var inheritedMedia=mock(Group.class);when(inheritedMedia.getName()).thenReturn("media");inherited.add(inheritedMedia);
        assertTrue(founderEnabled().remove(candidate));assertEquals(List.of(media,glorious),nodes);
    }
    @Test void founderVariantIsHeldByDefault() throws Exception {
        nodes.clear();nodes.add(node("devotee-founder"));
        assertFalse(ranks.eligible(new Candidate(uuid,"Donor","default","devotee-founder")));
        verify(manager,never()).saveUser(any());
    }
    @Test void explicitlyProtectedGroupStillHoldsWithBroaderCleanupEnabled() throws Exception {
        nodes.clear();nodes.add(node("devotee-founder"));
        var media=mock(Group.class);when(media.getName()).thenReturn("media");inherited.add(media);
        var config=new YamlConfiguration();config.set("entitlements.enabled",true);config.set("tebex.api-key","private-test-key");
        config.set("entitlements.delivery-server-id",2147009);config.set("entitlements.devotee-package-ids",List.of(7053397));
        config.set("entitlements.reconcile-founder-variant",true);config.set("entitlements.allow-direct-removal-from-protected-groups",true);
        config.set("entitlements.protected-groups",List.of("media"));
        var adapter=new LuckPermsRanks(api,ReconciliationSettings.load(config),()->stopped);
        assertFalse(adapter.eligible(new Candidate(uuid,"Donor","default","devotee-founder")));
        assertFalse(adapter.remove(new Candidate(uuid,"Donor","default","devotee-founder")));verify(manager,never()).saveUser(any());
    }
    @Test void explicitlyProtectedGroupStillHoldsEvenWithBroaderCleanupEnabled() throws Exception {
        var config=new YamlConfiguration();config.set("entitlements.enabled",true);config.set("tebex.api-key","private-test-key");
        config.set("entitlements.delivery-server-id",2147009);config.set("entitlements.devotee-package-ids",List.of(7053397));
        config.set("entitlements.reconcile-founder-variant",true);config.set("entitlements.allow-direct-removal-from-protected-groups",true);
        config.set("entitlements.protected-groups",List.of("media"));
        var media=mock(Group.class);when(media.getName()).thenReturn("media");inherited.add(media);
        var adapter=new LuckPermsRanks(api,ReconciliationSettings.load(config),()->stopped);
        assertFalse(adapter.remove(candidate));assertEquals(List.of(devotee),nodes);verify(manager,never()).saveUser(any());
    }
    @Test void targetedFounderVariantLeavesOtherDirectSubscriptionUntouched() throws Exception {
        Node founderDevotee=node("devotee-founder");nodes.add(founderDevotee);
        assertTrue(founderEnabled().remove(new Candidate(uuid,"Donor","default","devotee-founder")));
        assertEquals(List.of(devotee),nodes);
    }
    @Test void founderTemporaryOrContextualReplacementSurvivesMutation() throws Exception {
        nodes.clear();Node original=node("devotee-founder"),replacement=node("devotee-founder");nodes.add(original);
        when(replacement.hasExpiry()).thenReturn(true);
        doAnswer(call->{nodes.clear();nodes.add(replacement);nodes.removeIf(call.getArgument(0));return null;})
                .when(data).clear(org.mockito.ArgumentMatchers.<Predicate<? super Node>>any());
        assertFalse(founderEnabled().remove(new Candidate(uuid,"Donor","default","devotee-founder")));
        assertEquals(List.of(replacement),nodes);verify(manager,never()).saveUser(any());
    }
    @Test void founderPrimaryFallsBackToRemainingFounderRank() throws Exception {
        nodes.clear();nodes.add(node("devotee-founder"));nodes.add(node("avid-founder"));
        var avidFounder=mock(Group.class);when(avidFounder.getName()).thenReturn("avid-founder");when(avidFounder.getWeight()).thenReturn(OptionalInt.of(16));
        when(groups.getGroup("avid-founder")).thenReturn(avidFounder);when(user.getPrimaryGroup()).thenReturn("devotee-founder");
        when(user.setPrimaryGroup("avid-founder")).thenReturn(DataMutateResult.SUCCESS);
        assertTrue(founderEnabled().remove(new Candidate(uuid,"Donor","devotee-founder","devotee-founder")));
        verify(user).setPrimaryGroup("avid-founder");
    }
}
