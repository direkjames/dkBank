package dev.direk.dkbank.tier;

import dev.direk.dkbank.interest.InterestPlan;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;

/**
 * One bank tier from tiers.yml.
 *
 * @param id          the key in tiers.yml, lower case; stored in the database and used in permissions
 * @param rank        position in tiers.yml, 0 for the first (default) tier; higher is better
 * @param displayName MiniMessage name shown to players
 * @param icon        item shown in menus (material name)
 * @param cost        price to upgrade to this tier, paid from the bank balance
 * @param buyable     false for tiers only given by permission (ranks, donor perks)
 * @param maxBalance  most money the bank can hold, or null for no limit
 * @param plan        the interest this tier earns
 */
public record Tier(String id, int rank, String displayName, String icon, BigDecimal cost, boolean buyable,
                   @Nullable BigDecimal maxBalance, InterestPlan plan) {

    public static final String PERMISSION_PREFIX = "dkbank.tier.";

    /** The permission that gives this tier: {@code dkbank.tier.<id>}. */
    public String permission() {
        return PERMISSION_PREFIX + id;
    }

    public boolean isAbove(Tier other) {
        return rank > other.rank;
    }
}
