package com.enthusia.donors.relay;

import java.time.Instant;
import java.util.*;

/** Claim before sending. An uncertain claim is never automatically retried. */
public final class RelayDispatcher {
    @FunctionalInterface public interface Prepare {Runnable prepare(RelayEvent event,List<UUID> recipients) throws Exception;}
    private final RelayStore store;private final String source,proxy,key;
    public RelayDispatcher(RelayStore store,String source,String proxy,String key) {
        if(!RelayEvent.safeId(source) || !RelayEvent.safeId(proxy) || key==null || key.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<32)
            throw new IllegalArgumentException("Invalid relay identity or signing key");
        this.store=store;this.source=source;this.proxy=proxy;this.key=key;
    }
    public int poll(List<UUID> audience,Prepare prepare) throws Exception {
        List<UUID> recipients=List.copyOf(audience);if(recipients.isEmpty())return 0;
        int delivered=0;
        for(RelayEvent event:store.pending(source,proxy,20)) {
            if(!event.verify(source,key,Instant.now())) {store.reject(source,event.id(),proxy);continue;}
            Runnable send;
            try {send=Objects.requireNonNull(prepare.prepare(event,recipients));}
            catch(Exception invalid) {store.reject(source,event.id(),proxy);continue;}
            if(!store.claim(event,proxy))continue;
            try {send.run();}
            catch(Exception uncertain) {store.finish(event,proxy,false);continue;}
            store.finish(event,proxy,true);delivered++;
        }
        return delivered;
    }
}
