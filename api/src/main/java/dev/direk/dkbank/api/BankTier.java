package dev.direk.dkbank.api;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;

/**
 * A bank tier from tiers.yml.
 *
 * @param id          the name in tiers.yml, lower case
 * @param rank        0 for the first tier; higher is better
 * @param displayName name shown to players, in MiniMessage format
 * @param cost        upgrade price, paid from the bank
 * @param buyable     false for tiers only given by the {@code dkbank.tier.<id>} permission
 * @param maxBalance  most money the bank can hold, or null for no limit
 * @param onlineRate  interest in percent per online period
 * @param offlineRate interest in percent per offline period
 * @param interestCap only the balance up to this earns interest, or null for no cap
 */
public record BankTier(String id, int rank, String displayName, BigDecimal cost, boolean buyable,
                       @Nullable BigDecimal maxBalance, BigDecimal onlineRate, BigDecimal offlineRate,
                       @Nullable BigDecimal interestCap) {

    /** @return the permission that gives this tier: {@code dkbank.tier.<id>} */
    public String permission() {
        return "dkbank.tier." + id;
    }
}
