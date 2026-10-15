package com.enthusia.donors.infrastructure.entitlement;

import com.enthusia.donors.entitlement.Reconciliation.Candidate;
import net.luckperms.api.*;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.model.data.*;
import net.luckperms.api.model.group.*;
import net.luckperms.api.model.user.*;
import net.luckperms.api.node.*;
import net.luckperms.api.query.QueryOptionsRegistry;
import net.luckperms.api.util.Tristate;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
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
        Node node = mock(Node.class); when(node.getKey()).thenReturn("group." + group); when(node.getValue()).thenReturn(true);
        var context = mock(ImmutableContextSet.class); when(context.isEmpty()).thenReturn(true); when(node.getContexts()).thenReturn(context);
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
        nodes.add(devotee);
        ranks = new LuckPermsRanks(api, ReconciliationSettings.load(config), () -> stopped);
    }
    @Test void removesOnlyTheDirectDevoteeNodeAndSavesOfflineUser() throws Exception {
        Node avid = node("avid"); nodes.add(avid);
        assertTrue(ranks.remove(candidate)); assertEquals(List.of(avid), nodes);
        verify(data).remove(devotee); verify(manager).saveUser(user);
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
}
