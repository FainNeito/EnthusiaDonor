package com.enthusia.donors.sandbox.official.notifications;

import com.enthusia.donors.relay.RelayEvent;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Instant;

/** Channels are independent; a failed relay publication never blocks a Discord job. */
public final class NotificationDispatcher {
    @FunctionalInterface public interface Publisher {void publish(RelayEvent event) throws Exception;}
    @FunctionalInterface public interface Discord {String send(String payload);}
    private final NotificationStore store;private final Publisher publisher;private final Discord discord;
    public NotificationDispatcher(NotificationStore store,Publisher publisher,Discord discord) {this.store=store;this.publisher=publisher;this.discord=discord;}
    public int pump(Instant now,boolean chatEnabled,boolean discordEnabled) throws Exception {
        int errors=0;
        for(NotificationStore.Job job:store.pending()) {
            if(Thread.currentThread().isInterrupted())break;
            try {
                if(job.channel().equals("chat")) {
                    if(!chatEnabled)continue;
                    RelayEvent event=RelayEvent.parse(job.payload());
                    if(event.expiresAt()<=now.toEpochMilli()) {store.finish(job,"EXPIRED");continue;}
                    try {publisher.publish(event);store.finish(job,"PUBLISHED");}
                    catch(SQLIntegrityConstraintViolationException conflict) {store.finish(job,"FAILED");}
                } else {
                    if(!discordEnabled || !store.claim(job))continue;
                    String outcome=job.expiresAt()<=now.toEpochMilli()?"EXPIRED":discord.send(job.payload());
                    store.finish(job,outcome);
                }
            } catch(Exception unavailable) {errors++;}
        }
        return errors;
    }
}
