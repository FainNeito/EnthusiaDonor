package com.enthusia.donors.tebex;

import com.enthusia.donors.config.DonorsConfig;
import com.enthusia.donors.model.PaymentRecord;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.logging.Logger;

public final class TebexClient {
    private static final URI PAYMENTS_URI = URI.create("https://plugin.tebex.io/payments");

    private final Logger logger;

    public TebexClient(Logger logger) {
        this.logger = logger;
    }

    public CompletableFuture<List<PaymentRecord>> fetchPayments(DonorsConfig config) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(config.timeoutSeconds()))
                .build();
        return fetchPage(client, config, 1, new ArrayList<>());
    }

    /** Two identical strict scans avoid finalizing from silently dropped or shifting pages. */
    public CompletableFuture<StableAwardScan> fetchVerifiedAwardPayments(DonorsConfig config) {
        Instant startedAt=Instant.now();
        HttpClient client=HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(config.timeoutSeconds())).build();
        return fetchAwardPage(client,config,1,new AwardScan()).thenCompose(first ->
                fetchAwardPage(client,config,1,new AwardScan()).thenApply(second -> {
                    if(!canonical(first).equals(canonical(second)))throw new TebexException("Award payment history changed during verification");
                    return new StableAwardScan(startedAt,Instant.now(),second,config.tebexApiKey());
                }));
    }
    private static java.util.Map<String,PaymentRecord> canonical(List<PaymentRecord> records) {
        var values=new java.util.TreeMap<String,PaymentRecord>();
        for(var p:records) {
            var canonical=new PaymentRecord(p.paymentIdHash(),p.playerUuid(),p.playerName(),p.amount(),p.currency(),p.status(),p.createdAt(),
                    p.refundedOrChargeback(),p.manualPayment(),p.packageIds(),Instant.EPOCH);
            if(values.putIfAbsent(p.paymentIdHash(),canonical)!=null)throw new TebexException("Duplicate award payment in paginated history");
        }
        return values;
    }
    private static final class AwardScan {
        int total=-1, last=-1, perPage=-1;
        final List<PaymentRecord> payments=new ArrayList<>();
    }
    private CompletableFuture<List<PaymentRecord>> fetchAwardPage(HttpClient client,DonorsConfig config,int page,AwardScan scan) {
        if(page>config.maxPages())return CompletableFuture.failedFuture(new TebexException("Award history exceeds max-pages"));
        URI uri=URI.create(PAYMENTS_URI+"?paged=1&page="+page);
        HttpRequest request=HttpRequest.newBuilder(uri).timeout(java.time.Duration.ofSeconds(config.timeoutSeconds()))
                .header("Accept","application/json").header("User-Agent","EnthusiaDonors/1.0")
                .header("X-Tebex-Secret",config.tebexApiKey()).GET().build();
        return client.sendAsync(request,HttpResponse.BodyHandlers.ofString()).thenCompose(response -> {
            if(response.statusCode()<200 || response.statusCode()>=300)throw new TebexException("Award history request failed: HTTP "+response.statusCode());
            PageResult result=parseAwardPayments(response.body());
            if(result.currentPage()!=page)throw new TebexException("Award history page number changed");
            if(scan.total<0) {scan.total=result.total();scan.last=result.lastPage();scan.perPage=result.perPage();}
            else if(scan.total!=result.total() || scan.last!=result.lastPage() || scan.perPage!=result.perPage())
                throw new TebexException("Award history pagination changed during scan");
            scan.payments.addAll(result.payments());
            return result.hasNextPage()?fetchAwardPage(client,config,page+1,scan):CompletableFuture.completedFuture(scan.payments);
        });
    }

    public CompletableFuture<Set<String>> resolveExcludedPaymentIdHashes(DonorsConfig config) {
        if (config.excludedTransactionIds().isEmpty()) {
            return CompletableFuture.completedFuture(Set.of());
        }
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(config.timeoutSeconds()))
                .build();
        List<CompletableFuture<String>> futures = config.excludedTransactionIds().stream()
                .map(transactionId -> fetchPaymentIdHash(client, config, transactionId))
                .toList();
        CompletableFuture<?>[] all = futures.toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(all).thenApply(ignored -> futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toUnmodifiableSet()));
    }

    private CompletableFuture<String> fetchPaymentIdHash(HttpClient client, DonorsConfig config, String transactionId) {
        String encoded = URLEncoder.encode(transactionId, StandardCharsets.UTF_8);
        URI uri = URI.create(PAYMENTS_URI + "/" + encoded);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(java.time.Duration.ofSeconds(config.timeoutSeconds()))
                .header("Accept", "application/json")
                .header("User-Agent", "EnthusiaDonors/1.0")
                .header("X-Tebex-Secret", config.tebexApiKey())
                .GET()
                .build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new TebexException("Could not resolve one configured excluded Tebex transaction; HTTP " + response.statusCode());
                    }
                    JsonObject payment = JsonParser.parseString(response.body()).getAsJsonObject();
                    return stringValue(payment, "id").map(id -> {
                        try {
                            return hashId(id);
                        } catch (Exception ex) {
                            throw new IllegalStateException("Unable to hash resolved Tebex payment ID", ex);
                        }
                    }).orElseThrow(() -> new TebexException("A configured excluded Tebex transaction had no payment ID"));
                });
    }

    private CompletableFuture<List<PaymentRecord>> fetchPage(
            HttpClient client,
            DonorsConfig config,
            int page,
            List<PaymentRecord> collected
    ) {
        if (page > config.maxPages()) {
            return CompletableFuture.failedFuture(new TebexException(
                    "Tebex history exceeds max-pages=" + config.maxPages() + "; refusing an incomplete leaderboard"));
        }

        URI uri = URI.create(PAYMENTS_URI + "?paged=1&page=" + URLEncoder.encode(String.valueOf(page), StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(java.time.Duration.ofSeconds(config.timeoutSeconds()))
                .header("Accept", "application/json")
                .header("User-Agent", "EnthusiaDonors/1.0")
                .header("X-Tebex-Secret", config.tebexApiKey())
                .GET()
                .build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenCompose(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        return CompletableFuture.failedFuture(new TebexException("Tebex returned HTTP " + response.statusCode()));
                    }
                    PageResult pageResult = parsePayments(response.body());
                    if (pageResult.currentPage() != page) {
                        return CompletableFuture.failedFuture(new TebexException("Tebex returned unexpected page " + pageResult.currentPage()));
                    }
                    collected.addAll(pageResult.payments());
                    if (pageResult.hasNextPage()) {
                        return fetchPage(client, config, page + 1, collected);
                    }
                    return CompletableFuture.completedFuture(collected);
                });
    }

    PageResult parsePayments(String body) {
        return parsePayments(body,false);
    }

    /** Strict award-source parsing: an omitted payment must never select the wrong winner. */
    PageResult parseAwardPayments(String body) {
        return parsePayments(body,true);
    }

    private PageResult parsePayments(String body, boolean strictAwardInput) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) throw new TebexException("Tebex paginated response was not an object");
        JsonObject object = root.getAsJsonObject();
        if (!object.has("data") || !object.get("data").isJsonArray())
            throw new TebexException("Tebex paginated response omitted payment data");
        JsonArray data = object.getAsJsonArray("data");
        int current = intValue(object, "current_page").orElseThrow(() -> new TebexException("Tebex paginated response omitted current_page"));
        int last = intValue(object, "last_page").orElseThrow(() -> new TebexException("Tebex paginated response omitted last_page"));
        if (current < 1 || last < current) throw new TebexException("Tebex pagination was inconsistent");
        int total=-1, perPage=-1;
        if(strictAwardInput) {
            total=intValue(object,"total").orElseThrow(()->new TebexException("Award history omitted total"));
            perPage=intValue(object,"per_page").orElseThrow(()->new TebexException("Award history omitted page size"));
            if(total<0 || perPage<1 || last!=Math.max(1,(int)(((long)total+perPage-1)/perPage)))
                throw new TebexException("Award history page counts inconsistent");
            long remaining=(long)total-(long)(current-1)*perPage;
            if(remaining<0 || data.size()!=Math.min(perPage,remaining))throw new TebexException("Award history page is incomplete");
        }
        boolean hasNext = current < last;
        if (hasNext && data.isEmpty()) throw new TebexException("Tebex returned an empty intermediate page");

        List<PaymentRecord> payments = new ArrayList<>();
        Instant importedAt = Instant.now();
        int skipped = 0;
        for (JsonElement element : data) {
            try {
                JsonObject raw=element.getAsJsonObject();
                if(strictAwardInput) {
                    if(stringValue(raw,"status").isEmpty() || stringValue(raw,"amount").isEmpty()
                            || !raw.has("currency") || !raw.get("currency").isJsonObject()
                            || stringValue(raw.getAsJsonObject("currency"),"iso_4217").isEmpty())
                        throw new TebexException("Award input omitted payment status, amount, or currency");
                    if(new BigDecimal(stringValue(raw,"amount").orElseThrow()).signum()<0)
                        throw new TebexException("Award input contained a negative amount");
                    if(!Set.of("complete","completed","successful","success","paid","refund","refunded","chargeback",
                            "cancelled","canceled","failed","pending","denied","void","voided")
                            .contains(normalizeStatus(stringValue(raw,"status").orElseThrow())))
                        throw new TebexException("Award input contained an unknown payment status");
                }
                Optional<PaymentRecord> parsed=parsePayment(raw, importedAt);
                if(strictAwardInput && parsed.isEmpty())throw new TebexException("Award input omitted a payment or player identity");
                parsed.ifPresent(payments::add);
            } catch (Exception ex) {
                if (ex instanceof TebexException invalidDate) throw invalidDate;
                if (strictAwardInput) throw new TebexException("Award input contained a malformed payment");
                skipped++;
            }
        }
        if (skipped > 0) {
            logger.warning("Skipped " + skipped + " Tebex payments with missing or invalid public player data.");
        }
        return new PageResult(payments, hasNext, current, total, last, perPage);
    }

    Optional<PaymentRecord> parsePayment(JsonObject object, Instant importedAt) throws Exception {
        String rawId = stringValue(object, "id").orElse("");
        if (rawId.isBlank()) {
            return Optional.empty();
        }

        JsonObject player = object.has("player") && object.get("player").isJsonObject()
                ? object.getAsJsonObject("player")
                : new JsonObject();
        Optional<UUID> uuid = stringValue(player, "uuid").flatMap(this::parseUuid);
        String name = stringValue(player, "name").orElse("Unknown");
        if (uuid.isEmpty()) {
            return Optional.empty();
        }

        BigDecimal amount = new BigDecimal(stringValue(object, "amount").orElse("0")).max(BigDecimal.ZERO);
        String status = stringValue(object, "status").orElse("Unknown");
        boolean invalidStatus = switch (normalizeStatus(status)) {
            case "complete", "completed", "successful", "success", "paid" -> false;
            case "refund", "refunded", "chargeback", "cancelled", "canceled", "failed" -> true;
            default -> true;
        };

        String currency = "";
        if (object.has("currency") && object.get("currency").isJsonObject()) {
            currency = stringValue(object.getAsJsonObject("currency"), "iso_4217").orElse("");
        }

        Optional<Instant> createdAt = parseInstant(stringValue(object, "date").orElse(null));
        if (createdAt.isEmpty()) throw new TebexException("Tebex payment date was missing or invalid");
        return Optional.of(new PaymentRecord(
                hashId(rawId),
                uuid.get(),
                name,
                amount,
                currency,
                status,
                createdAt.get(),
                invalidStatus,
                isManualPayment(object),
                packageIds(object),
                importedAt
        ));
    }

    private List<Integer> packageIds(JsonObject object) {
        if (!object.has("packages") || !object.get("packages").isJsonArray()) {
            return List.of();
        }
        List<Integer> ids = new ArrayList<>();
        for (JsonElement element : object.getAsJsonArray("packages")) {
            if (!element.isJsonObject()) {
                continue;
            }
            intValue(element.getAsJsonObject(), "id").ifPresent(ids::add);
        }
        return List.copyOf(ids);
    }

    private boolean isManualPayment(JsonObject object) {
        if (booleanValue(object, "manual").orElse(false)) {
            return true;
        }
        return stringValue(object, "type").map(type -> type.toLowerCase(Locale.ROOT).contains("manual")).orElse(false)
                || stringValue(object, "payment_type").map(type -> type.toLowerCase(Locale.ROOT).contains("manual")).orElse(false)
                || stringValue(object, "source").map(type -> type.toLowerCase(Locale.ROOT).contains("manual")).orElse(false);
    }

    private Optional<Instant> parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(OffsetDateTime.parse(value).toInstant());
        } catch (Exception ignored) {
            try {
                return Optional.of(OffsetDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")).toInstant());
            } catch (Exception ignoredAgain) {
                return Optional.empty();
            }
        }
    }

    private Optional<UUID> parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim();
        if (!normalized.contains("-") && normalized.length() == 32) {
            normalized = normalized.substring(0, 8) + "-" + normalized.substring(8, 12) + "-"
                    + normalized.substring(12, 16) + "-" + normalized.substring(16, 20) + "-"
                    + normalized.substring(20);
        }
        try {
            return Optional.of(UUID.fromString(normalized));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private String hashId(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private Optional<String> stringValue(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return Optional.empty();
        }
        JsonElement value = object.get(key);
        if (value.isJsonPrimitive()) {
            return Optional.of(value.getAsString());
        }
        return Optional.empty();
    }

    private Optional<Integer> intValue(JsonObject object, String key) {
        return stringValue(object, key).map(Integer::parseInt);
    }

    private Optional<Boolean> booleanValue(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull() || !object.get(key).isJsonPrimitive()) {
            return Optional.empty();
        }
        return Optional.of(object.get(key).getAsBoolean());
    }

    private String normalizeStatus(String status) {
        return status == null ? "" : status.trim().toLowerCase(Locale.ROOT).replace(" ", "_");
    }

    record PageResult(List<PaymentRecord> payments, boolean hasNextPage, int currentPage,int total,int lastPage,int perPage) {
    }

    public static final class TebexException extends RuntimeException {
        public TebexException(String message) {
            super(message);
        }
    }
}
