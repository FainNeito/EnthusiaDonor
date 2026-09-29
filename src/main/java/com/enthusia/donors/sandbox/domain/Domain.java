package com.enthusia.donors.sandbox.domain;

import java.time.*;
import java.util.*;

/** Public-safe, immutable values. No payment provider identities or credentials belong here. */
public final class Domain {
    private Domain() { }
    public enum Product { AVID, DEVOTEE;
        public String display() { return this == AVID ? "Avid" : "Devotee"; }
        public static Product parse(String text) {
            return switch (text.toLowerCase(Locale.ROOT)) {
                case "avid" -> AVID; case "devotee" -> DEVOTEE;
                default -> throw new IllegalArgumentException("Use avid or devotee.");
            };
        }
    }
    public enum Kind { PURCHASE, SUBSCRIPTION, GIFT, RENEWAL }
    public enum Status { PAID, REFUNDED, CHARGEBACK }
    public record Person(UUID uuid, String name, boolean synthetic) {
        public Person { Objects.requireNonNull(uuid); Objects.requireNonNull(name);
            if (!name.matches("[A-Za-z0-9_. -]{1,32}")) throw new IllegalArgumentException("Invalid Minecraft display name."); }
        public static Person synthetic(String name) {
            return new Person(UUID.nameUUIDFromBytes(("EnthusiaDonorsTest:" + name.toLowerCase(Locale.ROOT))
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)), name, true);
        }
    }
    public record Payment(String id, UUID buyer, UUID recipient, Product product, Kind kind,
                          long cents, long paidAt, long termEnd, Status status) {
        public Payment {
            if (id == null || !id.matches("[A-Za-z0-9_:.-]{1,160}")) throw new IllegalArgumentException("Invalid test event ID.");
            Objects.requireNonNull(buyer); Objects.requireNonNull(recipient); Objects.requireNonNull(product);
            Objects.requireNonNull(kind); Objects.requireNonNull(status);
            if (cents < 0 || cents > 100_000_000L) throw new IllegalArgumentException("Amount must be between 0 and 1,000,000.");
            if (paidAt < 0 || termEnd < 0) throw new IllegalArgumentException("Invalid event timestamp.");
            if (kind == Kind.GIFT && buyer.equals(recipient)) throw new IllegalArgumentException("Choose a different gift recipient.");
            if (kind != Kind.GIFT && !buyer.equals(recipient) && kind != Kind.RENEWAL)
                throw new IllegalArgumentException("Different buyer and recipient require a gift event.");
            if ((kind == Kind.RENEWAL || kind == Kind.SUBSCRIPTION) && product != Product.DEVOTEE)
                throw new IllegalArgumentException("Only Devotee is a subscription.");
        }
        public boolean gift() { return !buyer.equals(recipient); }
        public Payment withStatus(Status next) { return new Payment(id,buyer,recipient,product,kind,cents,paidAt,termEnd,next); }
        public Payment withTermEnd(long end) { return new Payment(id,buyer,recipient,product,kind,cents,paidAt,end,status); }
    }
    public record Award(String key, String month, UUID winner, long cents, long awardedAt, boolean manual) { }
    public record Ranked(Person person, long cents, int rank, long reachedAt) { }
    public record Profile(Person person, long alltimeCents, long monthlyCents, int alltimeRank, int monthlyRank,
                          boolean avid, boolean devotee, boolean renewalCancelled, long devoteeUntil,
                          long firstSupportedAt, int giftsSent, int giftsReceived, List<Award> awards) {
        public boolean glorious() { return !awards.isEmpty(); }
        public long monthlyWins() { return awards.stream().filter(a -> !a.manual()).count(); }
        public String rankText() {
            List<String> names = new ArrayList<>();
            if (avid) names.add("Avid"); if (devotee) names.add("Devotee"); if (glorious()) names.add("Glorious");
            return names.isEmpty() ? "Member" : String.join(" + ", names);
        }
    }
    /** Only accessed by the serial engine; values in maps are immutable. */
    public static final class State {
        public int schema = 1;
        public String zone = "America/Chicago";
        public long now;
        public String openMonth;
        public Map<UUID,Person> people = new LinkedHashMap<>();
        public Map<String,Payment> payments = new LinkedHashMap<>();
        public Map<String,Award> awards = new LinkedHashMap<>();
        public Set<UUID> cancelled = new LinkedHashSet<>();
        public State copy() {
            State s = new State(); s.schema=schema; s.zone=zone; s.now=now; s.openMonth=openMonth;
            s.people.putAll(people); s.payments.putAll(payments); s.awards.putAll(awards); s.cancelled.addAll(cancelled);
            return s;
        }
        public void validate() {
            if (schema != 1) throw new IllegalStateException("Unsupported sandbox schema; keep the database for inspection.");
            ZoneId z = ZoneId.of(zone);
            if (now < 0 || !YearMonth.from(Instant.ofEpochMilli(now).atZone(z)).toString().equals(openMonth))
                throw new IllegalStateException("Invalid sandbox clock.");
            Objects.requireNonNull(people); Objects.requireNonNull(payments); Objects.requireNonNull(awards); Objects.requireNonNull(cancelled);
            payments.forEach((id,p) -> {
                if (!id.equals(p.id()) || !people.containsKey(p.buyer()) || !people.containsKey(p.recipient()))
                    throw new IllegalStateException("Invalid sandbox payment relationship.");
            });
            awards.forEach((id,a) -> {
                if (!id.equals(a.key()) || !people.containsKey(a.winner())) throw new IllegalStateException("Invalid award relationship.");
            });
        }
    }
    public record View(long now, String month, String zone, Map<UUID,Person> people,
                       List<Payment> payments, List<Award> awards, Map<UUID,Profile> profiles,
                       List<Ranked> monthly, List<Ranked> alltime) {
        public Optional<Person> byName(String name) { return people.values().stream().filter(p -> p.name().equalsIgnoreCase(name)).findFirst(); }
        public Profile profile(UUID uuid) { return profiles.get(uuid); }
        public List<Payment> history(UUID uuid, String which) {
            return payments.stream().filter(p -> switch (which) {
                case "sent" -> p.gift() && p.buyer().equals(uuid);
                case "received" -> p.gift() && p.recipient().equals(uuid);
                default -> p.buyer().equals(uuid);
            }).sorted(Comparator.comparingLong(Payment::paidAt).reversed().thenComparing(Payment::id)).toList();
        }
    }
}
