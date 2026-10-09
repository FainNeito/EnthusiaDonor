package com.enthusia.donors.sandbox.domain;

import com.enthusia.donors.sandbox.domain.Domain.*;
import java.time.*;
import java.util.*;

/** Pure accounting: gifts count for the payer, not the recipient. Month windows are [start,end). */
public final class Ledger {
    public static final long DEFAULT_GLORIOUS_MINIMUM_CENTS=3000;
    private Ledger() { }
    public static State empty(Instant now, ZoneId zone) {
        State s=new State(); s.now=now.toEpochMilli(); s.zone=zone.getId();
        s.openMonth=YearMonth.from(now.atZone(zone)).toString(); return s;
    }
    public static boolean add(State s, String id, Person buyer, Person recipient, Product product,
                              Kind kind, long cents, long paidAt, int termDays) {
        if (s.payments.containsKey(id)) return false;
        if (paidAt > s.now) throw new IllegalArgumentException("Cannot create a future payment.");
        if (termDays < 1 || termDays > 366) throw new IllegalArgumentException("Invalid test subscription length.");
        if (kind == Kind.RENEWAL && s.payments.values().stream().noneMatch(p -> p.recipient().equals(recipient.uuid())
                && p.product()==Product.DEVOTEE && p.status()==Status.PAID))
            throw new IllegalArgumentException("Create a Devotee purchase or gift before testing a renewal.");
        long previousEnd=s.payments.values().stream().filter(p -> p.recipient().equals(recipient.uuid())
                && p.product()==Product.DEVOTEE && p.status()==Status.PAID).mapToLong(Payment::termEnd).max().orElse(0);
        long end=product==Product.DEVOTEE ? Math.addExact(Math.max(previousEnd,paidAt), Duration.ofDays(termDays).toMillis()) : 0;
        Payment p=new Payment(id,buyer.uuid(),recipient.uuid(),product,kind,cents,paidAt,end,Status.PAID);
        s.people.put(buyer.uuid(),buyer); s.people.put(recipient.uuid(),recipient); s.payments.put(id,p);
        if (product==Product.DEVOTEE) s.cancelled.remove(recipient.uuid());
        return true;
    }
    public static List<Ranked> rank(State s, YearMonth month) {
        ZoneId zone=ZoneId.of(s.zone);
        long start=month==null ? Long.MIN_VALUE : month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli();
        long end=month==null ? Long.MAX_VALUE : month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli();
        Map<UUID,Long> totals=new HashMap<>(), reached=new HashMap<>();
        for (Payment p : s.payments.values()) {
            if (p.status()!=Status.PAID || p.cents()<=0 || p.paidAt()<start || p.paidAt()>=end || p.paidAt()>s.now) continue;
            totals.merge(p.buyer(),p.cents(),Math::addExact); reached.merge(p.buyer(),p.paidAt(),Math::max);
        }
        List<UUID> ids=new ArrayList<>(totals.keySet());
        ids.sort(Comparator.<UUID>comparingLong(totals::get).reversed()
                .thenComparingLong(reached::get).thenComparing(UUID::toString));
        List<Ranked> ranked=new ArrayList<>();
        for (int i=0;i<ids.size();i++) { UUID id=ids.get(i); ranked.add(new Ranked(s.people.get(id),totals.get(id),i+1,reached.get(id))); }
        return List.copyOf(ranked);
    }
    public static Payment reverse(State s, String paymentId, Status next) {
        if (next==Status.PAID) throw new IllegalArgumentException("Use a new payment to simulate a purchase.");
        Payment old=Objects.requireNonNull(s.payments.get(paymentId),"Unknown test payment.");
        if (old.status()!=Status.PAID) throw new IllegalArgumentException("This payment is already reversed.");
        Payment changed=old.withStatus(next); s.payments.put(paymentId,changed);
        // Permanent Glorious awards deliberately remain untouched.
        return changed;
    }
    public static void cancel(State s, UUID recipient) {
        boolean exists=s.payments.values().stream().anyMatch(p -> p.recipient().equals(recipient)
                && p.product()==Product.DEVOTEE && p.status()==Status.PAID && p.termEnd()>s.now);
        if (!exists) throw new IllegalArgumentException("No active test Devotee subscription.");
        s.cancelled.add(recipient); // Cancellation is not early removal of paid access.
    }
    public static void expire(State s, UUID recipient) {
        if (!s.people.containsKey(recipient)) throw new IllegalArgumentException("Unknown test player.");
        boolean found=s.payments.values().stream().anyMatch(p -> p.recipient().equals(recipient) && p.product()==Product.DEVOTEE);
        if (!found) throw new IllegalArgumentException("No test Devotee history for this player.");
        s.payments.replaceAll((id,p) -> p.recipient().equals(recipient) && p.product()==Product.DEVOTEE
                ? p.withTermEnd(Math.min(p.termEnd(),s.now)) : p);
        s.cancelled.add(recipient);
    }
    public static Award manualGlorious(State s, Person person) {
        s.people.put(person.uuid(),person);
        String key="manual:"+person.uuid();
        return s.awards.computeIfAbsent(key,k -> new Award(k,s.openMonth,person.uuid(),0,s.now,true));
    }
    /** Advance a virtual clock; never alter the server's clock or an operating-system clock. */
    public static List<Award> advanceTo(State s, long nextTime) {
        return advanceTo(s,nextTime,DEFAULT_GLORIOUS_MINIMUM_CENTS);
    }
    public static List<Award> advanceTo(State s, long nextTime, long minimumMonthlyCents) {
        if(minimumMonthlyCents<0) throw new IllegalArgumentException("Glorious minimum must be non-negative.");
        if (nextTime<s.now) throw new IllegalArgumentException("The sandbox clock cannot go backwards.");
        YearMonth open=YearMonth.parse(s.openMonth), target=YearMonth.from(Instant.ofEpochMilli(nextTime).atZone(ZoneId.of(s.zone)));
        if (target.isAfter(open.plusMonths(24))) throw new IllegalArgumentException("Advance at most 24 months at a time.");
        List<Award> created=new ArrayList<>();
        for (YearMonth m=open; m.isBefore(target); m=m.plusMonths(1)) {
            String key="monthly:"+m;
            if (s.awards.containsKey(key)) continue;
            List<Ranked> board=rank(s,m);
            if (!board.isEmpty() && board.getFirst().cents()>=minimumMonthlyCents) {
                Ranked top=board.getFirst();
                long boundary=m.plusMonths(1).atDay(1).atStartOfDay(ZoneId.of(s.zone)).toInstant().toEpochMilli();
                Award award=new Award(key,m.toString(),top.person().uuid(),top.cents(),boundary,false);
                s.awards.put(key,award); created.add(award);
            }
        }
        s.now=nextTime; s.openMonth=target.toString(); return List.copyOf(created);
    }
    public static List<Award> closeMonth(State s) {
        return closeMonth(s,DEFAULT_GLORIOUS_MINIMUM_CENTS);
    }
    public static List<Award> closeMonth(State s, long minimumMonthlyCents) {
        return advanceTo(s,YearMonth.parse(s.openMonth).plusMonths(1).atDay(1)
                .atStartOfDay(ZoneId.of(s.zone)).toInstant().toEpochMilli(),minimumMonthlyCents);
    }
    public static View view(State s) {
        List<Ranked> monthly=rank(s,YearMonth.parse(s.openMonth)), alltime=rank(s,null);
        Map<UUID,Ranked> mi=new HashMap<>(), ai=new HashMap<>();
        monthly.forEach(r -> mi.put(r.person().uuid(),r)); alltime.forEach(r -> ai.put(r.person().uuid(),r));
        Map<UUID,Profile> profiles=new LinkedHashMap<>();
        for (Person person : s.people.values()) {
            UUID id=person.uuid(); Ranked a=ai.get(id), m=mi.get(id);
            List<Payment> paid=s.payments.values().stream().filter(p -> p.status()==Status.PAID && p.paidAt()<=s.now).toList();
            boolean avid=paid.stream().anyMatch(p -> p.recipient().equals(id) && p.product()==Product.AVID);
            long until=paid.stream().filter(p -> p.recipient().equals(id) && p.product()==Product.DEVOTEE)
                    .mapToLong(Payment::termEnd).max().orElse(0);
            long since=paid.stream().filter(p -> p.buyer().equals(id) && p.cents()>0).mapToLong(Payment::paidAt).min().orElse(0);
            int sent=(int)paid.stream().filter(p -> p.gift() && p.buyer().equals(id)).count();
            int received=(int)paid.stream().filter(p -> p.gift() && p.recipient().equals(id)).count();
            List<Award> awards=s.awards.values().stream().filter(w -> w.winner().equals(id))
                    .sorted(Comparator.comparingLong(Award::awardedAt).reversed()).toList();
            profiles.put(id,new Profile(person,a==null?0:a.cents(),m==null?0:m.cents(),a==null?0:a.rank(),m==null?0:m.rank(),
                    avid,until>s.now,s.cancelled.contains(id),until,since,sent,received,awards));
        }
        return new View(s.now,s.openMonth,s.zone,Map.copyOf(s.people),List.copyOf(s.payments.values()),
                List.copyOf(s.awards.values()),Map.copyOf(profiles),monthly,alltime);
    }
}
