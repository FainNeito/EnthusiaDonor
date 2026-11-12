package com.enthusia.donors.sandbox.official.notifications;

import com.google.gson.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Offline parser for signed Tebex event fixtures. Not connected to a listener or dispatcher. */
public final class TebexWebhookEvidence {
    public enum Kind { PAYMENT, RENEWAL }
    public record Product(String name, String recipientId, String recipientName) { }
    public record Observation(Kind kind, String transactionId, String customerId, String customerName,
                              List<Product> products) {
        public Observation { products = List.copyOf(products); }
    }
    private TebexWebhookEvidence() { }

    /** Validate raw request bytes before parsing; never log the secret or raw payment body. */
    public static Optional<Observation> parse(byte[] body, String signature, String secret) {
        if (!verify(body,signature,secret)) return Optional.empty();
        try {
            JsonObject root = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
            String type = text(root, "type");
            Kind kind;
            JsonObject subject = object(root, "subject");
            if (type.equals("payment.completed")) kind = Kind.PAYMENT;
            else if (type.equals("recurring-payment.renewed")) {
                kind = Kind.RENEWAL;
                subject = object(subject, "last_payment");
            } else return Optional.empty();
            String transaction = text(subject, "transaction_id");
            if (transaction.isBlank()) return Optional.empty();
            JsonObject customer = object(object(subject, "customer"), "username");
            List<Product> products = new ArrayList<>();
            JsonElement values = subject.get("products");
            if (values != null && values.isJsonArray()) for (JsonElement value : values.getAsJsonArray()) {
                if (!value.isJsonObject()) continue;
                JsonObject product = value.getAsJsonObject(), recipient = object(product, "username");
                products.add(new Product(text(product, "name"), text(recipient, "id"), text(recipient, "username")));
            }
            return Optional.of(new Observation(kind, transaction, text(customer, "id"), text(customer, "username"), products));
        } catch (Exception invalid) { return Optional.empty(); }
    }
    /** Tebex signs the hex SHA-256 of the exact raw body using HMAC-SHA256. */
    public static boolean verify(byte[] body, String signature, String secret) {
        if (body == null || body.length > 262144 || secret == null || secret.isBlank()
                || signature == null || !signature.matches("(?i)[0-9a-f]{64}")) return false;
        try {
            String bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(bodyHash.getBytes(StandardCharsets.US_ASCII));
            return MessageDigest.isEqual(expected, HexFormat.of().parseHex(signature.toLowerCase(Locale.ROOT)));
        } catch (Exception invalid) { return false; }
    }
    private static JsonObject object(JsonObject parent, String key) {
        JsonElement e = parent.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
    }
    private static String text(JsonObject parent, String key) {
        JsonElement e = parent.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : "";
    }
}
