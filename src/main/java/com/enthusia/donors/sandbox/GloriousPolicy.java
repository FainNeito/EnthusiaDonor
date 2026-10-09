package com.enthusia.donors.sandbox;

import com.enthusia.donors.sandbox.domain.Ledger;
import org.bukkit.configuration.ConfigurationSection;
import java.math.BigDecimal;

/** Monetary eligibility shared by monthly award entry points. */
public record GloriousPolicy(long minimumMonthlyCents) {
    public GloriousPolicy {
        if(minimumMonthlyCents<0) throw new IllegalArgumentException("Glorious minimum must be non-negative.");
    }
    public static GloriousPolicy load(ConfigurationSection config) {
        Object value=config.get("glorious.minimum-monthly-donation");
        if(value==null) return new GloriousPolicy(Ledger.DEFAULT_GLORIOUS_MINIMUM_CENTS);
        try {
            return new GloriousPolicy(new BigDecimal(value.toString()).movePointRight(2).longValueExact());
        } catch(IllegalArgumentException | ArithmeticException ex) {
            throw new IllegalArgumentException("glorious.minimum-monthly-donation must be a non-negative amount with at most two decimal places.",ex);
        }
    }
    public String minimumAmount() {return BigDecimal.valueOf(minimumMonthlyCents,2).toPlainString();}
}
