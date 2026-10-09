package com.enthusia.donors.network;

import com.enthusia.donors.model.DonorEntry;
import com.google.gson.Gson;
import java.time.*;
import java.util.*;
import java.math.BigDecimal;

public record NetworkSnapshot(int schemaVersion,String sourceId,long revision,long generatedAt,
                              String month,String timezone,Display display,List<DonorEntry> donors) {
    private static final Gson JSON=new Gson();
    public record Display(String currencySymbol,boolean showCents,int topSize,String emptyName,String emptyAmount,String emptyRank) {
        public Display {
            Objects.requireNonNull(currencySymbol);Objects.requireNonNull(emptyName);
            Objects.requireNonNull(emptyAmount);Objects.requireNonNull(emptyRank);
            if(topSize<1 || topSize>1000)throw new IllegalArgumentException("Invalid snapshot display size");
        }
    }
    public NetworkSnapshot {
        if(schemaVersion!=1 || sourceId==null || !sourceId.matches("[a-z0-9_.-]{1,64}") || revision<0 || generatedAt<=0)
            throw new IllegalArgumentException("Invalid network snapshot metadata");
        Objects.requireNonNull(display);donors=List.copyOf(donors);
        ZoneId zone=ZoneId.of(timezone);YearMonth parsed=YearMonth.parse(month);
        if(!parsed.equals(YearMonth.from(Instant.ofEpochMilli(generatedAt).atZone(zone))))
            throw new IllegalArgumentException("Snapshot month differs from publication time");
        Set<UUID> ids=new HashSet<>();Set<Integer> allRanks=new HashSet<>(),monthlyRanks=new HashSet<>();
        for(DonorEntry d:donors) {
            if(d.uuid()==null || d.name()==null || d.name().isBlank() || d.name().length()>64 || !ids.add(d.uuid())
                    || d.alltimeTotal()==null || d.monthlyTotal()==null || d.alltimeTotal().signum()<0
                    || d.monthlyTotal().signum()<0 || d.monthlyTotal().compareTo(d.alltimeTotal())>0
                    || d.alltimeRank()<1 || !allRanks.add(d.alltimeRank())
                    || (d.monthlyTotal().signum()>0?(d.monthlyRank()<1 || !monthlyRanks.add(d.monthlyRank())):d.monthlyRank()!=0))
                throw new IllegalArgumentException("Invalid donor projection");
        }
        validateRanks(allRanks);validateRanks(monthlyRanks);
        validateOrder(donors.stream().sorted(Comparator.comparingInt(DonorEntry::alltimeRank)).map(DonorEntry::alltimeTotal).toList());
        validateOrder(donors.stream().filter(d -> d.monthlyRank()>0).sorted(Comparator.comparingInt(DonorEntry::monthlyRank)).map(DonorEntry::monthlyTotal).toList());
    }
    private static void validateRanks(Set<Integer> ranks) {
        for(int i=1;i<=ranks.size();i++)if(!ranks.contains(i))throw new IllegalArgumentException("Noncontiguous donor ranks");
    }
    private static void validateOrder(List<BigDecimal> amounts) {
        for(int i=1;i<amounts.size();i++)if(amounts.get(i).compareTo(amounts.get(i-1))>0)
            throw new IllegalArgumentException("Donor ranks disagree with totals");
    }
    public NetworkSnapshot withRevision(long next) {return new NetworkSnapshot(schemaVersion,sourceId,next,generatedAt,month,timezone,display,donors);}
    public String json() {return JSON.toJson(this);}
    public static NetworkSnapshot parse(String text) {
        if(text==null || text.length()>8_000_000)throw new IllegalArgumentException("Missing or oversized network snapshot");
        return Objects.requireNonNull(JSON.fromJson(text,NetworkSnapshot.class));
    }
}
