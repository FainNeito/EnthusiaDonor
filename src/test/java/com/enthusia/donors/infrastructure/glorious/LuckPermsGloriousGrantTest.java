package com.enthusia.donors.infrastructure.glorious;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.model.data.*;
import net.luckperms.api.model.group.*;
import net.luckperms.api.model.user.*;
import net.luckperms.api.node.*;
import net.luckperms.api.node.types.InheritanceNode;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LuckPermsGloriousGrantTest {
    final UUID id=UUID.randomUUID(); final LuckPerms api=mock(LuckPerms.class);
    final UserManager manager=mock(UserManager.class); final GroupManager groups=mock(GroupManager.class);
    final User user=mock(User.class); final NodeMap data=mock(NodeMap.class);
    final List<Node> nodes=new ArrayList<>(); final Node glorious=node("glorious");
    LuckPermsGloriousGrant grant;
    static Node node(String name) {
        Node n=mock(InheritanceNode.class); when(n.getKey()).thenReturn("group."+name); when(n.getValue()).thenReturn(true);
        var contexts=mock(ImmutableContextSet.class);when(contexts.isEmpty()).thenReturn(true);when(n.getContexts()).thenReturn(contexts);return n;
    }
    @BeforeEach void setup() {
        when(api.getUserManager()).thenReturn(manager);when(api.getGroupManager()).thenReturn(groups);
        when(groups.getGroup("glorious")).thenReturn(mock(Group.class));when(api.getMessagingService()).thenReturn(Optional.empty());
        when(manager.loadUser(id)).thenReturn(CompletableFuture.completedFuture(user));when(manager.saveUser(user)).thenReturn(CompletableFuture.completedFuture(null));
        when(user.data()).thenReturn(data);when(data.toCollection()).thenAnswer(x->List.copyOf(nodes));
        when(data.add(any())).thenAnswer(x->{nodes.add(x.getArgument(0));return DataMutateResult.SUCCESS;});
        var registry=mock(NodeBuilderRegistry.class);var builder=mock(InheritanceNode.Builder.class);
        when(api.getNodeBuilderRegistry()).thenReturn(registry);when(registry.forInheritance()).thenReturn(builder);
        when(builder.group("glorious")).thenReturn(builder);when(builder.build()).thenReturn((InheritanceNode)glorious);
        grant=new LuckPermsGloriousGrant(api,()->false);
    }
    @Test void addsPermanentGroupWithoutRemovingOtherRanks() {
        Node founder=node("founder");nodes.add(founder);
        assertEquals(LuckPermsGloriousGrant.Result.SAVED,grant.grant(id));
        assertEquals(List.of(founder,glorious),nodes);verify(manager).saveUser(user);verify(user,never()).setPrimaryGroup(any());
    }
    @Test void existingPermanentMembershipIsIdempotent() {
        nodes.add(glorious);assertEquals(LuckPermsGloriousGrant.Result.ALREADY_PRESENT,grant.grant(id));
        verify(manager,never()).saveUser(any());
    }
    @Test void conflictingMembershipOrMissingGroupIsHeld() {
        nodes.add(glorious);when(glorious.hasExpiry()).thenReturn(true);
        assertEquals(LuckPermsGloriousGrant.Result.HELD,grant.grant(id));
        nodes.clear();when(groups.getGroup("glorious")).thenReturn(null);
        assertEquals(LuckPermsGloriousGrant.Result.HELD,grant.grant(id));verify(manager,never()).saveUser(any());
    }
    @Test void saveFailureIsUncertainAndNeverReportedAsSuccess() {
        when(manager.saveUser(user)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("offline")));
        assertEquals(LuckPermsGloriousGrant.Result.UNCERTAIN,grant.grant(id));
    }
}
