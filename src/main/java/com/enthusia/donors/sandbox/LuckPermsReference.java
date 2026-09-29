package com.enthusia.donors.sandbox;

import com.enthusia.donors.sandbox.domain.Domain.Product;
import java.util.List;

/**
 * Read-only reference captured from the supplied LuckPerms sync snapshot.
 * This class never calls LuckPerms and cannot grant or remove groups.
 */
public final class LuckPermsReference {
    public record RankGroups(String normal, int normalWeight, String founder, int founderWeight) { }

    public static final RankGroups AVID =
            new RankGroups("avid", 15, "avid-founder", 16);
    public static final RankGroups DEVOTEE =
            new RankGroups("devotee", 20, "devotee-founder", 21);
    public static final RankGroups GLORIOUS =
            new RankGroups("glorious", 25, "glorious-founders", 26);

    private LuckPermsReference() { }

    public static String groupFor(Product product, boolean founder) {
        return select(product == Product.AVID ? AVID : DEVOTEE, founder);
    }
    public static String gloriousGroup(boolean founder) {
        return select(GLORIOUS, founder);
    }

    private static String select(RankGroups groups, boolean founder) {
        return founder ? groups.founder() : groups.normal();
    }

    public static List<String> summaryLines() {
        return List.of(
                "LuckPerms dry-run reference — NO groups are changed by this test build.",
                describe("Avid", AVID),
                describe("Devotee", DEVOTEE),
                describe("Glorious", GLORIOUS),
                "Founder-styled groups mirror the supplied snapshot's gold-star variants.",
                "Snapshot warning: glorious-founders references parent group.glorious-founder,",
                "but group glorious-founder does not exist in the supplied group list."
        );
    }

    private static String describe(String label, RankGroups groups) {
        return label + ": " + groups.normal() + " (weight " + groups.normalWeight()
                + ") | Founder: " + groups.founder() + " (weight " + groups.founderWeight() + ")";
    }
}
