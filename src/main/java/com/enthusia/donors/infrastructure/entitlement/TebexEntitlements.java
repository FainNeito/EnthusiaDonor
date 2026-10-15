package com.enthusia.donors.infrastructure.entitlement;

import com.enthusia.donors.entitlement.Reconciliation.*;
import com.google.gson.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** GET-only official API adapter. A failed/malformed lookup is never an empty entitlement set. */
public final class TebexEntitlements {
    @FunctionalInterface public interface Transport { JsonElement get(String path, boolean tebex) throws Exception; }
    private final Transport transport;
    private final Set<Long> packages;
    private final long serverId;
    private final int maxHistory;

    public TebexEntitlements(Transport transport, Set<Long> packages, long serverId, int maxHistory) {
        this.transport = transport; this.packages = Set.copyOf(packages); this.serverId = serverId; this.maxHistory = maxHistory;
        if (packages.isEmpty() || packages.stream().anyMatch(id -> id <= 0) || serverId <= 0 || maxHistory < 1)
            throw new IllegalArgumentException("Explicit server, subscription packages and bounded history required");
    }
    public static Transport http(String key, Duration timeout) {
        var client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
        var retryAt = new java.util.concurrent.atomic.AtomicReference<>(Instant.EPOCH);
        return (path, tebex) -> {
            if (Instant.now().isBefore(retryAt.get())) throw new java.io.IOException("Official API retry delay active");
            URI uri = URI.create((tebex ? "https://plugin.tebex.io" : "https://api.mojang.com") + path);
            var request = HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "application/json").GET();
            if (tebex) request.header("X-Tebex-Secret", key);
            var future = client.sendAsync(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            HttpResponse<byte[]> response;
            try { response = future.get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS); }
            catch (Exception unavailable) { future.cancel(true); throw unavailable; }
            if (response.statusCode() == 429) {
                Instant next = Instant.now().plusSeconds(60);
                String value = response.headers().firstValue("Retry-After").orElse("");
                try {
                    Instant requested = value.matches("[0-9]+") ? Instant.now().plusSeconds(Long.parseLong(value))
                            : ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                    if (requested.isAfter(next)) next = requested;
                } catch (RuntimeException invalidHeader) { /* Conservative one-minute minimum. */ }
                retryAt.set(next);
            } else if (response.statusCode() == 401 || response.statusCode() == 403) retryAt.set(Instant.now().plusSeconds(60));
            if (response.statusCode() != 200) throw new java.io.IOException("Official lookup failed: HTTP " + response.statusCode());
            if (response.body().length > 2_000_000) throw new java.io.IOException("Official response exceeds bound");
            return JsonParser.parseString(new String(response.body(), StandardCharsets.UTF_8));
        };
    }
    public void validate() throws Exception {
        var info = object(transport.get("/information", true));
        if (number(object(info.get("server")), "id") != serverId) throw new IllegalStateException("Wrong Tebex delivery server");
        for (long id : packages) {
            var pack = object(transport.get("/package/" + id, true));
            if (number(pack, "id") != id || !text(pack, "type").equals("subscription")
                    || (pack.has("disabled") && pack.get("disabled").getAsBoolean()))
                throw new IllegalStateException("Unverified subscription package");
        }
    }
    public Evidence check(Candidate candidate) throws Exception {
        if (!candidate.name().matches("[A-Za-z0-9_]{1,16}")) return unknown();
        var minecraft = object(transport.get("/users/profiles/minecraft/" + candidate.name(), false));
        if (!text(minecraft, "id").equalsIgnoreCase(candidate.uuid().toString().replace("-", ""))
                || !text(minecraft, "name").equalsIgnoreCase(candidate.name())) return unknown();
        var lookup = object(transport.get("/user/" + candidate.name(), true));
        var player = object(lookup.get("player"));
        String customer = text(player, "id");
        if (!customer.matches("[A-Za-z0-9-]{1,64}") || !text(player, "username").equalsIgnoreCase(candidate.name())) return unknown();
        var active = array(transport.get("/player/" + customer + "/packages", true));
        for (var item : active) {
            long id = number(object(object(item).get("package")), "id");
            if (packages.contains(id)) return new Evidence(State.ACTIVE, "");
        }
        var payments = array(lookup.get("payments"));
        if (payments.isEmpty() || payments.size() > maxHistory) return unknown();
        var signature = new ArrayList<String>();
        boolean paid = false;
        for (var item : payments) {
            var payment = object(item);
            String transaction = text(payment, "txn_id");
            if (!transaction.matches("[A-Za-z0-9_-]{1,128}")) return unknown();
            long status = number(payment, "status");
            long time = number(payment, "time");
            if (time < 0 || time > Instant.now().getEpochSecond()) return unknown();
            signature.add(transaction + ":" + time + ":" + status);
            if (status != 1 || paid) continue;
            var detail = object(transport.get("/payments/" + transaction, true));
            if (!text(detail, "status").equalsIgnoreCase("Complete")) continue;
            var recipient = object(detail.get("player"));
            if (!text(recipient, "name").equalsIgnoreCase(candidate.name())
                    || !text(recipient, "uuid").replace("-", "").equalsIgnoreCase(customer.replace("-", ""))) return unknown();
            if (detail.get("amount").getAsBigDecimal().signum() <= 0) continue;
            String gateway = text(object(detail.get("gateway")), "name");
            if (gateway.toLowerCase(Locale.ROOT).contains("manual")) continue;
            for (var purchased : array(detail.get("packages"))) {
                if (packages.contains(number(object(purchased), "id"))) paid = true;
            }
        }
        if (!paid) return unknown();
        Collections.sort(signature);
        String proof = serverId + ":" + packages.stream().sorted().toList() + ":" + customer + ":" + candidate.uuid()
                + ":" + candidate.name().toLowerCase(Locale.ROOT) + ":" + candidate.primaryGroup() + ":" + String.join("|", signature);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(proof.getBytes(StandardCharsets.UTF_8)));
        return new Evidence(State.INACTIVE, hash);
    }
    private static Evidence unknown() { return new Evidence(State.UNKNOWN, ""); }
    private static JsonObject object(JsonElement element) {
        if (element == null || !element.isJsonObject()) throw new IllegalArgumentException("Expected official object");
        return element.getAsJsonObject();
    }
    private static JsonArray array(JsonElement element) {
        if (element == null || !element.isJsonArray()) throw new IllegalArgumentException("Expected complete official array");
        return element.getAsJsonArray();
    }
    private static String text(JsonObject value, String key) {
        var element = value.get(key);
        if (element == null || !element.isJsonPrimitive() || element.getAsString().isBlank())
            throw new IllegalArgumentException("Missing official identity or field");
        return element.getAsString();
    }
    private static long number(JsonObject value, String key) {
        return new java.math.BigDecimal(text(value, key)).longValueExact();
    }
}
