package dev.direk.dkbank.interest;

import dev.direk.dkbank.money.Money;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Interest calculations. Every result is rounded down to the cent, so interest never creates a fraction
 * of a cent out of nothing.
 * <ul>
 *     <li><b>Online</b> (simple, per payout cycle): {@code base × rate × activeTime / period}. A full hour
 *     of play at 1% pays exactly 1%.</li>
 *     <li><b>Offline and AFK</b> (compounding): {@code base × ((1 + rate)^(time / period) − 1)}. Three days
 *     offline at 1% per day pays 3.0301%, the same as three daily payouts.</li>
 * </ul>
 * The base is the lowest balance since the last payout, limited to the plan's cap.
 */
public final class InterestMath {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private InterestMath() {
    }

    /** The part of the balance that earns interest. */
    public static BigDecimal earningBase(BigDecimal lowestBalance, @Nullable BigDecimal cap) {
        BigDecimal base = lowestBalance.max(BigDecimal.ZERO);
        return cap == null ? base : base.min(cap);
    }

    /** Online interest for {@code activeMillis} of play, prorated against the period. */
    public static BigDecimal online(BigDecimal base, BigDecimal ratePercent, long activeMillis, long periodMillis) {
        if (base.signum() <= 0 || ratePercent.signum() <= 0 || activeMillis <= 0) return Money.ZERO;
        BigDecimal fraction = BigDecimal.valueOf(activeMillis).divide(BigDecimal.valueOf(periodMillis), MC);
        return base.multiply(ratePercent, MC).divide(HUNDRED, MC).multiply(fraction, MC).setScale(Money.SCALE, RoundingMode.DOWN);
    }

    /** Compounding interest for {@code elapsedMillis}, at {@code ratePercent} per period. */
    public static BigDecimal compounding(BigDecimal base, BigDecimal ratePercent, long elapsedMillis, long periodMillis) {
        if (base.signum() <= 0 || ratePercent.signum() <= 0 || elapsedMillis <= 0) return Money.ZERO;
        BigDecimal step = BigDecimal.ONE.add(ratePercent.divide(HUNDRED, MC));
        long wholePeriods = elapsedMillis / periodMillis;
        double partOfPeriod = (double) (elapsedMillis % periodMillis) / periodMillis;

        // Whole periods exactly; the leftover part of a period with double precision (far below a cent).
        BigDecimal factor = wholePeriods > 0 ? step.pow((int) Math.min(wholePeriods, 100_000), MC) : BigDecimal.ONE;
        if (partOfPeriod > 0) {
            factor = factor.multiply(BigDecimal.valueOf(Math.pow(step.doubleValue(), partOfPeriod)), MC);
        }
        return base.multiply(factor.subtract(BigDecimal.ONE), MC).setScale(Money.SCALE, RoundingMode.DOWN);
    }

    /**
     * Applies the payout limits.
     *
     * @param room how much more the balance may hold (maximum balance minus the current balance)
     */
    public static BigDecimal limit(BigDecimal interest, @Nullable BigDecimal maxPerPayout, BigDecimal room) {
        BigDecimal result = interest.max(Money.ZERO);
        if (maxPerPayout != null) result = result.min(maxPerPayout);
        return Money.floor(result.min(room.max(Money.ZERO)));
    }
}
