package com.enthusia.donors.sandbox.skin;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MojangProfilesTest {
    private static final UUID PLAYER = UUID.fromString("20f05082-8450-4841-9fe4-a36aa89249c1");
    private static final String ID = "20f05082845048419fe4a36aa89249c1";
    private static final String SKIN = "http://textures.minecraft.net/texture/"
            + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test void acceptsOnlyMatchingOfficialProfileAndTextureHost() {
        assertEquals("https://textures.minecraft.net/texture/"
                + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                MojangProfiles.parseTexture(response(ID, SKIN), PLAYER).orElseThrow().toString());
        assertTrue(MojangProfiles.parseTexture(response("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", SKIN), PLAYER).isEmpty());
        assertTrue(MojangProfiles.parseTexture(response(ID, "https://example.com/texture/"
                + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"), PLAYER).isEmpty());
    }

    @Test void rejectsMalformedNameLookupAndUrls() {
        assertEquals(PLAYER, MojangProfiles.parseUuid("{\"id\":\"" + ID + "\"}").orElseThrow());
        assertTrue(MojangProfiles.parseUuid("{\"id\":\"not-a-uuid\"}").isEmpty());
        assertTrue(MojangProfiles.validateTextureUrl("https://textures.minecraft.net.evil/texture/"
                + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef").isEmpty());
    }

    private static String response(String id, String url) {
        String texture = "{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"}}}";
        String encoded = Base64.getEncoder().encodeToString(texture.getBytes(StandardCharsets.UTF_8));
        return "{\"id\":\"" + id + "\",\"properties\":[{\"name\":\"textures\",\"value\":\"" + encoded + "\"}]}";
    }
}
