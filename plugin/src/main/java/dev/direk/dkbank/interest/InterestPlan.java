package dev.direk.dkbank.interest;

import dev.direk.dkbank.money.Money;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;

/**
 * The interest a player earns. Periods and on/off switches come from config.yml; rates, cap and maximum
 * payout come from the player's bank tier.
 *
 * @param onlineEnabled  pay interest for time spent online
 * @param onlineRate     percent per {@code onlinePeriodMillis} of active play, e.g. 1 for 1%
 * @param onlinePeriodMillis how much playtime the online rate is for; also how often payouts happen
 * @param offlineEnabled pay interest for time offline (and AFK)
 * @param offlineRate    percent per {@code offlinePeriodMillis}, compounding
 * @param offlinePeriodMillis how long the offline rate is for, e.g. one day
 * @param offlineMaxMillis offline time that counts, at most (stops abandoned accounts growing forever)
 * @param cap            only the balance up to this earns interest; null for no cap
 * @param maxPerPayout   most interest in one payout; null for no limit
 */
public record InterestPlan(boolean onlineEnabled, BigDecimal onlineRate, long onlinePeriodMillis,
                           boolean offlineEnabled, BigDecimal offlineRate, long offlinePeriodMillis,
                           long offlineMaxMillis, @Nullable BigDecimal cap, @Nullable BigDecimal maxPerPayout) {

    public InterestPlan {
        if (onlinePeriodMillis <= 0) throw new IllegalArgumentException("online period must be positive");
        if (offlinePeriodMillis <= 0) throw new IllegalArgumentException("offline period must be positive");
        cap = cap == null || cap.signum() <= 0 ? null : Money.floor(cap);
        maxPerPayout = maxPerPayout == null || maxPerPayout.signum() <= 0 ? null : Money.floor(maxPerPayout);
    }

    /** This plan with a tier's rates and limits. */
    public InterestPlan withTier(BigDecimal onlineRate, BigDecimal offlineRate, @Nullable BigDecimal cap,
                                 @Nullable BigDecimal maxPerPayout) {
        return new InterestPlan(onlineEnabled, onlineRate, onlinePeriodMillis, offlineEnabled, offlineRate,
                offlinePeriodMillis, offlineMaxMillis, cap, maxPerPayout);
    }
}
