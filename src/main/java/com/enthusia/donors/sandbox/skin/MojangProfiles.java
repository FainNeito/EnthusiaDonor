package com.enthusia.donors.sandbox.skin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/** Bounded public profile reads; never asks Paper/authlib to refresh a profile. */
public final class MojangProfiles {
    private static final int MAX_RESPONSE_BYTES = 131_072;
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private MojangProfiles() { }

    public record TextureResult(URL url, String source) { }

    /** Tebex's stored UUID can differ from the current Java UUID; prefer a verified current name. */
    public static Optional<TextureResult> texture(UUID paymentUuid, String playerName, int timeoutSeconds) {
        Optional<UUID> currentUuid = uuidByName(playerName, timeoutSeconds);
        if (currentUuid.isPresent()) {
            Optional<URL> currentSkin = texture(currentUuid.get(), timeoutSeconds);
            if (currentSkin.isPresent()) return currentSkin.map(url -> new TextureResult(url,
                    currentUuid.get().equals(paymentUuid) ? "Mojang name and payment UUID" : "Mojang name"));
        }
        if (currentUuid.isEmpty() || !currentUuid.get().equals(paymentUuid))
            return texture(paymentUuid, timeoutSeconds).map(url -> new TextureResult(url, "payment UUID"));
        return Optional.empty();
    }

    public static Optional<URL> texture(UUID uuid, int timeoutSeconds) {
        String id = uuid.toString().replace("-", "");
        return request("https://sessionserver.mojang.com/session/minecraft/profile/" + id + "?unsigned=false", timeoutSeconds)
                .flatMap(body -> parseTexture(body, uuid));
    }

    public static Optional<UUID> uuidByName(String name, int timeoutSeconds) {
        if (name == null || !name.matches("[A-Za-z0-9_]{3,16}")) return Optional.empty();
        return request("https://api.mojang.com/users/profiles/minecraft/" + name, timeoutSeconds)
                .flatMap(MojangProfiles::parseUuid);
    }

    private static Optional<String> request(String url, int timeoutSeconds) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(Math.max(1, Math.min(10, timeoutSeconds))))
                    .header("Accept", "application/json")
                    .header("User-Agent", "EnthusiaDonors-Test/1")
                    .GET().build();
            HttpResponse<InputStream> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                if (response.statusCode() != 200) return Optional.empty();
                byte[] bytes = input.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) return Optional.empty();
                return Optional.of(new String(bytes, StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    static Optional<URL> parseTexture(String body, UUID expectedUuid) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (!parseId(root.get("id").getAsString()).filter(expectedUuid::equals).isPresent()) return Optional.empty();
            JsonArray properties = root.getAsJsonArray("properties");
            if (properties == null) return Optional.empty();
            for (JsonElement element : properties) {
                JsonObject property = element.getAsJsonObject();
                if (!"textures".equals(property.get("name").getAsString())) continue;
                byte[] decoded = Base64.getDecoder().decode(property.get("value").getAsString());
                if (decoded.length > MAX_RESPONSE_BYTES) return Optional.empty();
                JsonObject textures = JsonParser.parseString(new String(decoded, StandardCharsets.UTF_8))
                        .getAsJsonObject().getAsJsonObject("textures");
                if (textures == null || !textures.has("SKIN")) return Optional.empty();
                String raw = textures.getAsJsonObject("SKIN").get("url").getAsString();
                return validateTextureUrl(raw);
            }
        } catch (Exception ignored) { }
        return Optional.empty();
    }

    static Optional<UUID> parseUuid(String body) {
        try {
            return parseId(JsonParser.parseString(body).getAsJsonObject().get("id").getAsString());
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static Optional<UUID> parseId(String raw) {
        if (raw == null || !raw.matches("(?i)[0-9a-f]{32}")) return Optional.empty();
        String formatted = raw.substring(0, 8) + "-" + raw.substring(8, 12) + "-" + raw.substring(12, 16)
                + "-" + raw.substring(16, 20) + "-" + raw.substring(20);
        return Optional.of(UUID.fromString(formatted));
    }

    public static Optional<URL> validateTextureUrl(String raw) {
        try {
            URI uri = URI.create(raw);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || !"textures.minecraft.net".equalsIgnoreCase(uri.getHost())
                    || !uri.getPath().matches("/texture/[A-Fa-f0-9]{32,128}")
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null || uri.getPort() != -1)
                return Optional.empty();
            return Optional.of(URI.create("https://textures.minecraft.net" + uri.getPath()).toURL());
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }
}
