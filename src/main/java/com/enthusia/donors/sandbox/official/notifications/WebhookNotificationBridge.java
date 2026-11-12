package com.enthusia.donors.sandbox.official.notifications;

import com.enthusia.donors.model.PaymentRecord;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.function.Predicate;

/** Shared-store integration seam. No listener or production wiring is enabled by this class. */
public final class WebhookNotificationBridge {
    @FunctionalInterface public interface DetailLookup {
        CompletionStage<PaymentRecord> byTransaction(String transactionId);
    }
    @FunctionalInterface public interface Renderer {
        NotificationStore.Payload render(PaymentRecord payment, TebexWebhookEvidence.Observation event);
    }
    private final NotificationStore store;
    private final DetailLookup lookup;
    private final Predicate<PaymentRecord> eligible;
    private final Renderer renderer;
    private final boolean deferred;
    public WebhookNotificationBridge(NotificationStore store, DetailLookup lookup,
                                     Predicate<PaymentRecord> eligible, Renderer renderer) {
        this(store,lookup,eligible,renderer,false);
    }
    public WebhookNotificationBridge(NotificationStore store, DetailLookup lookup,
                                     Predicate<PaymentRecord> eligible, Renderer renderer, boolean deferred) {
        this.store=store; this.lookup=lookup; this.eligible=eligible; this.renderer=renderer; this.deferred=deferred;
    }
    /** Invalid events fail closed; successful observations use the same atomic seen/outbox tables as polling. */
    public CompletionStage<Integer> accept(byte[] body, String signature, String secret, Instant now) {
        Optional<TebexWebhookEvidence.Observation> event=TebexWebhookEvidence.parse(body,signature,secret);
        if(event.isEmpty()) return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid webhook event"));
        var evidence=event.orElseThrow();
        return lookup.byTransaction(evidence.transactionId()).thenApply(payment -> {
            if(payment==null || payment.paymentIdHash()==null || payment.paymentIdHash().isBlank())
                throw new IllegalStateException("Official payment identity unavailable");
            if(payment.createdAt()==null || payment.createdAt().isAfter(now))
                throw new IllegalStateException("Official payment timestamp is not ready");
            String status=payment.status()==null?"":payment.status().trim().toLowerCase(java.util.Locale.ROOT);
            if(!java.util.Set.of("complete","completed","successful","success","paid","refund","refunded","chargeback").contains(status))
                throw new IllegalStateException("Official payment status is not final");
            // Rendering may resolve a skin; never hold the shared source row lock while waiting.
            boolean allowed=eligible.test(payment);
            NotificationStore.Payload prepared=allowed?renderer.render(payment,evidence):null;
            try {
                if(deferred)return store.observeWebhookDeferred(payment,now,p->allowed,p->prepared,
                        evidence.kind()==TebexWebhookEvidence.Kind.RENEWAL);
                return store.observeWebhook(payment,now,p->allowed,p->prepared);
            } catch(SQLException failure) {throw new java.util.concurrent.CompletionException(failure);}
        });
    }
}
