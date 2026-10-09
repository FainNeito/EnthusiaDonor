package com.enthusia.donors.network;

import com.enthusia.donors.cache.DonorCache;
import com.enthusia.donors.model.*;
import java.time.*;
import java.math.BigDecimal;
import java.util.List;

public final class SharedSnapshotConsumer {
    private final String source;private final DonorCache cache;
    private NetworkSnapshot accepted;
    private volatile SnapshotRead.Status lastRead=SnapshotRead.Status.NOT_FOUND;
    public SharedSnapshotConsumer(String source,DonorCache cache) {this.source=source;this.cache=cache;}
    public synchronized long revision() {return accepted==null?0:accepted.revision();}
    public SnapshotRead.Status lastRead() {return lastRead;}
    public synchronized NetworkSnapshot accepted() {return accepted;}
    public synchronized boolean accept(SnapshotRead read,Instant now,int maxAgeSeconds) {
        lastRead=read.status();
        if(read.status()==SnapshotRead.Status.FAILED) {cache.markNetworkFailure();rollover(now);return false;}
        if(read.status()==SnapshotRead.Status.NOT_FOUND) {cache.markNetworkMissing();rollover(now);return false;}
        NetworkSnapshot candidate=read.snapshot();
        if(candidate==null || !source.equals(candidate.sourceId()) || candidate.revision()<1 || candidate.generatedAt()>now.plusSeconds(300).toEpochMilli()
                || (accepted!=null && candidate.revision()<accepted.revision())
                || (accepted!=null && candidate.revision()==accepted.revision() && !candidate.equals(accepted))) {
            lastRead=SnapshotRead.Status.FAILED;cache.markNetworkFailure();rollover(now);return false;
        }
        accepted=candidate;
        boolean current=YearMonth.now(Clock.fixed(now,ZoneId.of(candidate.timezone()))).toString().equals(candidate.month());
        boolean stale=!current || now.toEpochMilli()-candidate.generatedAt()>maxAgeSeconds*1000L;
        List<DonorEntry> donors=current?candidate.donors():withoutMonthly(candidate.donors());
        cache.replace(donors,Instant.ofEpochMilli(candidate.generatedAt()),stale?RefreshState.NETWORK_STALE:RefreshState.OK);
        return true;
    }
    private void rollover(Instant now) {
        if(accepted!=null && !YearMonth.from(now.atZone(ZoneId.of(accepted.timezone()))).toString().equals(accepted.month()))
            cache.replace(withoutMonthly(accepted.donors()),Instant.ofEpochMilli(accepted.generatedAt()),cache.state());
    }
    private static List<DonorEntry> withoutMonthly(List<DonorEntry> donors) {
        return donors.stream().map(d -> new DonorEntry(d.uuid(),d.name(),d.alltimeTotal(),BigDecimal.ZERO,d.alltimeRank(),0,d.updatedAt())).toList();
    }
}
