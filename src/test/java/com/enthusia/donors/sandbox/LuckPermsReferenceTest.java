package com.enthusia.donors.sandbox;

import com.enthusia.donors.sandbox.domain.Domain.Product;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LuckPermsReferenceTest {
    @Test void normalAndFounderGroupsMatchSuppliedSnapshot() {
        assertEquals("avid", LuckPermsReference.groupFor(Product.AVID, false));
        assertEquals("avid-founder", LuckPermsReference.groupFor(Product.AVID, true));
        assertEquals("devotee", LuckPermsReference.groupFor(Product.DEVOTEE, false));
        assertEquals("devotee-founder", LuckPermsReference.groupFor(Product.DEVOTEE, true));
        assertEquals("glorious", LuckPermsReference.gloriousGroup(false));
        assertEquals("glorious-founders", LuckPermsReference.gloriousGroup(true));
    }

    @Test void weightsMatchSuppliedSnapshot() {
        assertEquals(15, LuckPermsReference.AVID.normalWeight());
        assertEquals(16, LuckPermsReference.AVID.founderWeight());
        assertEquals(20, LuckPermsReference.DEVOTEE.normalWeight());
        assertEquals(21, LuckPermsReference.DEVOTEE.founderWeight());
        assertEquals(25, LuckPermsReference.GLORIOUS.normalWeight());
        assertEquals(26, LuckPermsReference.GLORIOUS.founderWeight());
    }

    @Test void summaryKeepsTestBuildReadOnlyAndFlagsSnapshotMismatch() {
        String summary = String.join("\n", LuckPermsReference.summaryLines());
        assertTrue(summary.contains("NO groups are changed"));
        assertTrue(summary.contains("glorious-founder"));
        assertTrue(summary.contains("does not exist"));
    }
}
