package dev.direk.dkbank.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Rules for money inside dkBank.
 * <ul>
 *     <li>Amounts are {@link BigDecimal} with exactly {@value #SCALE} decimals in code, and whole cents
 *     ({@code long}) in the database. No floating point anywhere money is stored or added up.</li>
 *     <li>Anything a player types is rounded <b>down</b> to the cent, so rounding can never create money.</li>
 *     <li>Fees are rounded <b>up</b> to the cent, so a fee is never shaved to zero by rounding.</li>
 * </ul>
 */
public final class Money {

    public static final int SCALE = 2;
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE);
    /**
     * Hard ceiling for any balance: one quadrillion. Far above any real economy, and far below the
     * {@code long} limit in cents, so sums of balances can't overflow.
     */
    public static final BigDecimal HARD_MAX = new BigDecimal("1000000000000000").setScale(SCALE);

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private Money() {
    }

    /** Rounds down to the cent (for amounts players enter). */
    public static BigDecimal floor(BigDecimal amount) {
        return amount.setScale(SCALE, RoundingMode.DOWN);
    }

    /** Converts to whole cents for storage. */
    public static long toCents(BigDecimal amount) {
        return amount.setScale(SCALE, RoundingMode.DOWN).movePointRight(SCALE).longValueExact();
    }

    /** Converts stored cents back to an amount. */
    public static BigDecimal fromCents(long cents) {
        return BigDecimal.valueOf(cents, SCALE);
    }

    /** A wallet balance from an economy plugin (which uses {@code double}), rounded down to the cent. */
    public static BigDecimal fromDouble(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return ZERO;
        return BigDecimal.valueOf(value).setScale(SCALE, RoundingMode.DOWN);
    }

    /**
     * @param percent e.g. {@code 2.5} for 2.5%
     * @return the fee on {@code amount}, rounded up to the cent; zero if the rate is zero or negative
     */
    public static BigDecimal fee(BigDecimal amount, BigDecimal percent) {
        if (percent.signum() <= 0) return ZERO;
        return amount.multiply(percent).divide(HUNDRED, SCALE, RoundingMode.UP);
    }

    /**
     * The largest amount that, plus its fee, fits in {@code available}. Used for "all" and "half" when a
     * fee is added on top (transfers).
     */
    public static BigDecimal largestWithFee(BigDecimal available, BigDecimal percent) {
        if (percent.signum() <= 0) return floor(available);
        BigDecimal factor = BigDecimal.ONE.add(percent.divide(HUNDRED, 10, RoundingMode.UP));
        BigDecimal amount = available.divide(factor, SCALE, RoundingMode.DOWN);
        // Fees round up, so step down a cent at a time if the estimate is a cent too high.
        while (amount.signum() > 0 && amount.add(fee(amount, percent)).compareTo(available) > 0) {
            amount = amount.subtract(new BigDecimal("0.01"));
        }
        return amount.max(ZERO);
    }
}
