package com.enthusia.donors.sandbox.placeholder;

import com.enthusia.donors.cache.DonorCache;
import com.enthusia.donors.cache.PlayerStatCache;
import com.enthusia.donors.model.DonorEntry;
import com.enthusia.donors.model.PlayerStatEntry;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Public donor placeholders read only the official Tebex cache; test purchases never enter this adapter. */
public final class OfficialPlaceholderValues {
    private OfficialPlaceholderValues() { }

    public record DisplaySettings(String currencySymbol, boolean showCents, String emptyName,
                                  String emptyAmount, String emptyRank, int topSize, boolean showAmounts) { }

    public static String resolve(UUID playerId, String parameter, DonorCache official,
                                 PlayerStatCache.Snapshot stats, DisplaySettings display) {
        if (parameter == null || official == null || stats == null || display == null) return null;
        DonorCache.Snapshot snapshot = official.snapshot();
        String key = parameter.toLowerCase(Locale.ROOT);
        return switch (key) {
            case "alltime_count" -> String.valueOf(snapshot.alltime().size());
            case "monthly_count" -> String.valueOf(snapshot.monthly().size());
            case "kills_count" -> String.valueOf(stats.kills().size());
            case "deaths_count" -> String.valueOf(stats.deaths().size());
            case "last_updated" -> snapshot.updatedAt() == null ? "Never" : date(snapshot.updatedAt());
            case "stats_last_updated" -> stats.updatedAt() == null ? "Never" : date(stats.updatedAt());
            case "status" -> official.state().name().toLowerCase(Locale.ROOT);
            default -> detail(playerId, key, snapshot, stats, display);
        };
    }

    private static String detail(UUID playerId, String key, DonorCache.Snapshot snapshot,
                                 PlayerStatCache.Snapshot stats, DisplaySettings display) {
        if (key.startsWith("player_")) return player(playerId, key.substring(7), snapshot, display);
        String[] parts = key.split("_");
        if (parts.length < 4 || !parts[1].equals("top")) return null;
        int rank = rank(parts[2]);
        if (rank < 1 || rank > display.topSize()) return null;
        String field = String.join("_", Arrays.copyOfRange(parts, 3, parts.length));
        return switch (parts[0]) {
            case "alltime" -> donor(snapshot.alltime(), rank, field, true, display);
            case "monthly" -> donor(snapshot.monthly(), rank, field, false, display);
            case "kills" -> stat(stats.topKills(rank).orElse(null), true, field, display);
            case "deaths" -> stat(stats.topDeaths(rank).orElse(null), false, field, display);
            default -> null;
        };
    }

    private static String player(UUID id, String field, DonorCache.Snapshot snapshot, DisplaySettings display) {
        DonorEntry entry = id == null ? null : snapshot.byUuid(id).orElse(null);
        return switch (field) {
            case "alltime_amount" -> amount(entry == null ? null : entry.alltimeTotal(), false, display);
            case "alltime_amount_raw" -> amount(entry == null ? null : entry.alltimeTotal(), true, display);
            case "alltime_rank" -> entry == null || entry.alltimeRank() < 1 ? display.emptyRank() : String.valueOf(entry.alltimeRank());
            case "monthly_amount" -> amount(entry == null ? null : entry.monthlyTotal(), false, display);
            case "monthly_amount_raw" -> amount(entry == null ? null : entry.monthlyTotal(), true, display);
            case "monthly_rank" -> entry == null || entry.monthlyRank() < 1 ? display.emptyRank() : String.valueOf(entry.monthlyRank());
            default -> null;
        };
    }

    private static String donor(List<DonorEntry> board, int rank, String field,
                                boolean alltime, DisplaySettings display) {
        DonorEntry entry = rank <= board.size() ? board.get(rank - 1) : null;
        return switch (field) {
            case "name" -> entry == null ? display.emptyName() : entry.name();
            case "uuid" -> entry == null ? "" : entry.uuid().toString();
            case "amount" -> amount(entry == null ? null : alltime ? entry.alltimeTotal() : entry.monthlyTotal(), false, display);
            case "amount_raw" -> amount(entry == null ? null : alltime ? entry.alltimeTotal() : entry.monthlyTotal(), true, display);
            case "rank" -> entry == null ? display.emptyRank() : String.valueOf(alltime ? entry.alltimeRank() : entry.monthlyRank());
            default -> null;
        };
    }

    private static String stat(PlayerStatEntry entry, boolean kills, String field, DisplaySettings display) {
        return switch (field) {
            case "name" -> entry == null ? display.emptyName() : entry.name();
            case "uuid" -> entry == null ? "" : entry.uuid().toString();
            case "kills" -> String.valueOf(entry == null ? 0 : entry.kills());
            case "deaths" -> String.valueOf(entry == null ? 0 : entry.deaths());
            case "value", "amount" -> String.valueOf(entry == null ? 0 : kills ? entry.kills() : entry.deaths());
            case "rank" -> entry == null ? display.emptyRank() : String.valueOf(kills ? entry.killsRank() : entry.deathsRank());
            default -> null;
        };
    }

    private static String amount(BigDecimal value, boolean raw, DisplaySettings display) {
        if (!display.showAmounts()) return "Hidden";
        if (value == null) return raw ? "0.00" : display.emptyAmount();
        String normalized = value.setScale(raw || display.showCents() ? 2 : 0, RoundingMode.HALF_UP).toPlainString();
        return raw ? normalized : display.currencySymbol() + normalized;
    }

    private static String date(Instant instant) {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(instant.atOffset(ZoneOffset.UTC));
    }

    private static int rank(String value) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ignored) { return -1; }
    }
}
