package com.enthusia.donors.infrastructure.glorious;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.node.Node;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Optional platform adapter. Construct only after confirming LuckPerms is available. */
public final class LuckPermsGloriousGrant {
    public enum Result { SAVED, ALREADY_PRESENT, HELD, UNCERTAIN }
    private final LuckPerms api;
    private final BooleanSupplier stopped;
    public LuckPermsGloriousGrant(LuckPerms api, BooleanSupplier stopped) { this.api=api;this.stopped=stopped; }

    /** Invoke after durably recording GRANTING. Any uncertain save remains held for operator review. */
    public Result grant(UUID player) {
        if(player==null || stopped.getAsBoolean() || api.getGroupManager().getGroup("glorious")==null) return Result.HELD;
        net.luckperms.api.model.user.User user;
        try { user=api.getUserManager().loadUser(player).get(30,TimeUnit.SECONDS); }
        catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); return Result.HELD; }
        catch(Exception unavailable) { return Result.HELD; }
        var nodes=user.data().toCollection().stream().filter(n->n.getKey().equals("group.glorious")).toList();
        if(nodes.size()==1 && nodes.getFirst().getValue() && !nodes.getFirst().hasExpiry() && nodes.getFirst().getContexts().isEmpty()) return Result.ALREADY_PRESENT;
        if(!nodes.isEmpty() || stopped.getAsBoolean()) return Result.HELD;
        Node node=api.getNodeBuilderRegistry().forInheritance().group("glorious").build();
        try {
            if(!user.data().add(node).wasSuccessful()) return Result.HELD;
            if(stopped.getAsBoolean()) return Result.UNCERTAIN;
            api.getUserManager().saveUser(user).get(30,TimeUnit.SECONDS);
            // A message failure after a successful save is not proof that the store failed.
            try { api.getMessagingService().ifPresent(service->service.pushUserUpdate(user)); } catch(RuntimeException ignored) { }
            return Result.SAVED;
        } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); return Result.UNCERTAIN; }
        catch(Exception uncertain) { return Result.UNCERTAIN; }
    }
}
