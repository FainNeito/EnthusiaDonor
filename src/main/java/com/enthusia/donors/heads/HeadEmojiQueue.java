package com.enthusia.donors.heads;

import com.enthusia.donors.model.DonorEntry;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Cosmetic work only. No pack publishing or command execution port exists. */
public final class HeadEmojiQueue {
    public record Owner(UUID uuid,String name) {
        public Owner {Objects.requireNonNull(uuid);if(name==null||!name.matches("[A-Za-z0-9_]{3,16}"))throw new IllegalArgumentException("Unsupported owner name");}
        public String id(){return "donor_head_"+uuid.toString().replace("-","");}
        public String permission(){return "enthusia.donorhead."+uuid.toString().replace("-","");}
        public String alias(){return ":"+name.toLowerCase(Locale.ROOT)+":";}
    }
    @FunctionalInterface public interface Identity {Optional<Owner> resolve(DonorEntry donor) throws Exception;}
    public interface Glyphs {boolean exists(Owner owner) throws Exception;void stage(Owner owner) throws Exception;}
    @FunctionalInterface public interface Permissions {void grant(Owner owner) throws Exception;}
    public record Result(int completed,int held,int failed) { }
    private final Identity identities;private final Glyphs glyphs;private final Permissions permissions;
    private final BooleanSupplier active;
    private final Set<UUID> completed=new HashSet<>();private int cursor;
    public HeadEmojiQueue(Identity identities,Glyphs glyphs,Permissions permissions){this(identities,glyphs,permissions,()->true);}
    public HeadEmojiQueue(Identity identities,Glyphs glyphs,Permissions permissions,BooleanSupplier active){this.identities=identities;this.glyphs=glyphs;this.permissions=permissions;this.active=active;}
    public synchronized Result accept(List<DonorEntry> donors,boolean verified,int limit) {
        if(!verified||donors.isEmpty())return new Result(0,0,0);
        if(limit<1||limit>50)throw new IllegalArgumentException("Batch size out of bounds");
        int done=0,held=0,failed=0,attempts=0,visited=0;
        while(visited<donors.size()&&attempts<limit){
            if(!active.getAsBoolean())break;
            int index=Math.floorMod(cursor++,donors.size());visited++;
            DonorEntry donor=donors.get(index);
            if(donor.uuid()==null||donor.alltimeTotal()==null||donor.alltimeTotal().signum()<=0||completed.contains(donor.uuid()))continue;
            attempts++;
            try {
                var resolved=identities.resolve(donor);
                if(resolved.isEmpty()){held++;continue;}
                if(!active.getAsBoolean())break;
                Owner owner=resolved.get();
                if(!glyphs.exists(owner))glyphs.stage(owner);
                permissions.grant(owner);
                completed.add(donor.uuid());done++;
            } catch(Exception unavailable){failed++;}
        }
        return new Result(done,held,failed);
    }
}
