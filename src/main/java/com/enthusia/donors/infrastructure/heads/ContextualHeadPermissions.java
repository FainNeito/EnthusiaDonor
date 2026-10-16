package com.enthusia.donors.infrastructure.heads;

import com.enthusia.donors.heads.HeadEmojiQueue;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.node.NodeEqualityPredicate;
import net.luckperms.api.node.types.PermissionNode;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Optional linking boundary, loaded only when owner permissions are enabled. */
public final class ContextualHeadPermissions {
    private ContextualHeadPermissions(){}
    public static HeadEmojiQueue.Permissions create(JavaPlugin plugin,String server,BooleanSupplier active){
        LuckPerms lp=plugin.getServer().getServicesManager().load(LuckPerms.class);
        if(lp==null)throw new IllegalStateException("Donor head permissions require LuckPerms");
        if(!lp.getContextManager().getStaticContext().contains("server",server))throw new IllegalArgumentException("Head permission server must match local LuckPerms context");
        return owner->{
            if(!active.getAsBoolean())throw new IllegalStateException("Closing");
            var user=lp.getUserManager().loadUser(owner.uuid()).get(10,TimeUnit.SECONDS);
            if(!active.getAsBoolean())throw new IllegalStateException("Closing");
            var node=PermissionNode.builder(owner.permission()).value(true).withContext("server",server).build();
            if(!user.data().contains(node,NodeEqualityPredicate.EXACT).asBoolean()){
                if(!user.data().add(node).wasSuccessful())throw new IllegalStateException("Permission refused");
            }
            // A failed prior save can leave the in-memory node present; always retry persistence.
            lp.getUserManager().saveUser(user).get(10,TimeUnit.SECONDS);
            lp.getMessagingService().ifPresent(service->service.pushUserUpdate(user));
        };
    }
}
