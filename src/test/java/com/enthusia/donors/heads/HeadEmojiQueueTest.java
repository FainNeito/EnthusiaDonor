package com.enthusia.donors.heads;

import com.enthusia.donors.model.DonorEntry;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HeadEmojiQueueTest {
    final UUID id=UUID.fromString("12345678-1234-4234-8234-123456789abc");
    DonorEntry donor(){return new DonorEntry(id,"Donor",BigDecimal.TEN,BigDecimal.TEN,1,1,1);}
    @Test void unverifiedSnapshotsCannotGenerateOrGrant() throws Exception {
        int[] calls={0};
        var q=new HeadEmojiQueue(d->{calls[0]++;return Optional.empty();},new Store(),o->calls[0]++);
        q.accept(List.of(donor()),false,5);
        assertEquals(0,calls[0]);
    }
    @Test void generatedGlyphIsGrantedOnlyToResolvedOwnerAndNeverRecreated() throws Exception {
        Store store=new Store();List<HeadEmojiQueue.Owner> grants=new ArrayList<>();
        var owner=new HeadEmojiQueue.Owner(id,"Donor");
        var q=new HeadEmojiQueue(d->Optional.of(owner),store,grants::add);
        q.accept(List.of(donor()),true,5);q.accept(List.of(donor()),true,5);
        assertEquals(1,store.creates);assertEquals(List.of(owner),grants);
    }
    @Test void unresolvedIdentityDoesNotGrantOrCreate() throws Exception {
        Store store=new Store();var q=new HeadEmojiQueue(d->Optional.empty(),store,o->fail());
        assertEquals(1,q.accept(List.of(donor()),true,5).held());assertEquals(0,store.creates);
    }
    @Test void permissionFailureRetriesWithoutRecreatingTexture() throws Exception {
        Store store=new Store();int[] calls={0};
        var q=new HeadEmojiQueue(d->Optional.of(new HeadEmojiQueue.Owner(id,"Donor")),store,o->{if(calls[0]++==0)throw new Exception("save failed");});
        assertEquals(1,q.accept(List.of(donor()),true,5).failed());
        assertEquals(1,q.accept(List.of(donor()),true,5).completed());assertEquals(1,store.creates);
    }
    @Test void noZeroTotalsAndPendingOwnerDoesNotStarveNextBatch() throws Exception {
        Store store=new Store();int[] calls={0};
        var q=new HeadEmojiQueue(d->{calls[0]++;return Optional.empty();},store,o->fail());
        var second=new DonorEntry(UUID.randomUUID(),"Other",BigDecimal.ONE,BigDecimal.ZERO,2,0,1);
        q.accept(List.of(donor(),second),true,1);q.accept(List.of(donor(),second),true,1);
        assertEquals(2,calls[0]);
        q.accept(List.of(new DonorEntry(id,"Donor",BigDecimal.ZERO,BigDecimal.ZERO,0,0,1)),true,5);
        assertEquals(2,calls[0]);
    }
    static class Store implements HeadEmojiQueue.Glyphs {
        Set<UUID> owners=new HashSet<>();int creates;
        public boolean exists(HeadEmojiQueue.Owner o){return owners.contains(o.uuid());}
        public void stage(HeadEmojiQueue.Owner o){owners.add(o.uuid());creates++;}
    }
    @Test void stoppingDuringResolutionCannotCreateOrGrant()throws Exception{
        boolean[] active={true};var store=new Store();
        var q=new HeadEmojiQueue(d->{active[0]=false;return Optional.of(new HeadEmojiQueue.Owner(id,"Donor"));},store,o->fail(),()->active[0]);
        q.accept(List.of(donor()),true,5);assertEquals(0,store.creates);
    }
}
