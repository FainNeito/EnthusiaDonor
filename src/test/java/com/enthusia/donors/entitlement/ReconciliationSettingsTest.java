package com.enthusia.donors.entitlement;

import com.enthusia.donors.infrastructure.entitlement.ReconciliationSettings;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ReconciliationSettingsTest {
    YamlConfiguration config() {
        var y = new YamlConfiguration(); y.set("entitlements.enabled", true);
        y.set("entitlements.delivery-server-id", 2147009); y.set("entitlements.devotee-package-ids", List.of(7053397));
        y.set("tebex.api-key", "private-test-key"); return y;
    }
    @Test void disabledByDefaultWithoutLuckPermsOrSecrets() {
        assertFalse(ReconciliationSettings.load(new YamlConfiguration()).enabled());
    }
    @Test void auditDefaultsAndMandatoryProtections() {
        var s = ReconciliationSettings.load(config()); assertFalse(s.enforce());
        assertEquals(15, s.interval().toMinutes()); assertEquals(60, s.confirmation().toMinutes());
        assertTrue(s.protectedGroups().containsAll(List.of("founder", "media", "glorious")));
        assertFalse(s.toString().contains("private-test-key"));
    }
    @Test void invalidOrMissingAuthorityRejected() {
        var y = config(); y.set("tebex.api-key", ""); assertThrows(Exception.class, () -> ReconciliationSettings.load(y));
        var empty = config(); empty.set("entitlements.devotee-package-ids", List.of());
        assertThrows(Exception.class, () -> ReconciliationSettings.load(empty));
    }
    @Test void invalidModeDurationsAndUuidRejected() {
        for (String key : List.of("mode", "confirmation-minutes", "check-interval-minutes", "protected-uuids")) {
            var y = config(); y.set("entitlements." + key, switch (key) {
                case "mode" -> "guess"; case "protected-uuids" -> List.of("not-a-uuid"); default -> 0;
            });
            assertThrows(Exception.class, () -> ReconciliationSettings.load(y));
        }
    }
    @Test void verifiedCustomersAreExplicitAndNormalized() {
        var y = config(); String id = "bf15f0ff-5c20-4476-918a-a525e28f4270";
        y.set("entitlements.verified-customers." + id, List.of("ABCD-1234", "former"));
        var settings = ReconciliationSettings.load(y);
        assertEquals(java.util.Set.of("abcd1234", "former"), settings.verifiedCustomers().get(java.util.UUID.fromString(id)));
    }
    @Test void ambiguousEmptyAndMalformedBindingsAreRejected() {
        String first = "bf15f0ff-5c20-4476-918a-a525e28f4270", second = "cf15f0ff-5c20-4476-918a-a525e28f4270";
        var duplicate = config(); duplicate.set("entitlements.verified-customers." + first, List.of("customer"));
        duplicate.set("entitlements.verified-customers." + second, List.of("customer"));
        assertThrows(Exception.class, () -> ReconciliationSettings.load(duplicate));
        var empty = config(); empty.set("entitlements.verified-customers." + first, List.of());
        assertThrows(Exception.class, () -> ReconciliationSettings.load(empty));
        var malformed = config(); malformed.set("entitlements.verified-customers." + first, List.of("../../other"));
        assertThrows(Exception.class, () -> ReconciliationSettings.load(malformed));
    }
}
