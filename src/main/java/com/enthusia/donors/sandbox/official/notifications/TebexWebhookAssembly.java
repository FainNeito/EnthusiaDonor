package com.enthusia.donors.sandbox.official.notifications;

import com.enthusia.donors.config.DonorsConfig;
import com.enthusia.donors.model.PaymentRecord;
import com.enthusia.donors.tebex.TebexClient;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.function.Predicate;

/** Shared durable processing for either explicitly selected transport. */
public final class TebexWebhookAssembly {
    private TebexWebhookAssembly() { }
    public static TebexHttpReceiver loopback(InetSocketAddress address, String secret,
            NotificationStore store, TebexClient client, DonorsConfig config,
            Predicate<PaymentRecord> eligible, WebhookNotificationBridge.Renderer renderer) throws IOException {
        return new TebexHttpReceiver(address,endpoint(secret,store,client,config,eligible,renderer));
    }
    public static TebexWebhookEndpoint endpoint(String secret,NotificationStore store,TebexClient client,DonorsConfig config,
            Predicate<PaymentRecord> eligible,WebhookNotificationBridge.Renderer renderer) {
        var bridge=new WebhookNotificationBridge(store,transaction->client.fetchWebhookPayment(config,transaction),eligible,renderer,true);
        return new TebexWebhookEndpoint(secret,bridge);
    }
}
