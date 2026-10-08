package dev.direk.dkbank.money;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * An amount as a player typed it: a fixed amount ({@code 1000}, {@code 1.5k}, {@code 2m}) or a share of
 * what's available ({@code all}, {@code half}). Shares are worked out later, against the real balance at
 * the moment the money moves.
 *
 * @param fixed    the amount, or null for a share
 * @param fraction the share of the available money ({@code 1} for all, {@code 0.5} for half), or null
 */
public record AmountInput(@Nullable BigDecimal fixed, @Nullable BigDecimal fraction) {

    public static final AmountInput ALL = new AmountInput(null, BigDecimal.ONE);
    public static final AmountInput HALF = new AmountInput(null, new BigDecimal("0.5"));

    private static final String[][] SUFFIXES = {
            {"k", "1000"}, {"m", "1000000"}, {"b", "1000000000"}, {"t", "1000000000000"}
    };

    /** Thrown for text that isn't a valid amount. The message is safe to show to players. */
    public static final class InvalidAmountException extends Exception {
        public InvalidAmountException(String message) {
            super(message);
        }
    }

    /**
     * Accepts {@code 1000}, {@code 1,000}, {@code 2500.75}, {@code 1.5k}, {@code 2m}, {@code 1b},
     * {@code 3t}, {@code all} and {@code half}. Extra decimals are dropped (rounded down).
     */
    public static AmountInput parse(String text) throws InvalidAmountException {
        if (text == null || text.isBlank()) throw new InvalidAmountException("No amount given");
        String s = text.trim().toLowerCase(Locale.ROOT).replace(",", "").replace("_", "");
        if (s.equals("all") || s.equals("max")) return ALL;
        if (s.equals("half")) return HALF;

        BigDecimal multiplier = BigDecimal.ONE;
        for (String[] suffix : SUFFIXES) {
            if (s.endsWith(suffix[0])) {
                multiplier = new BigDecimal(suffix[1]);
                s = s.substring(0, s.length() - 1);
                break;
            }
        }
        if (!s.matches("\\d+(\\.\\d+)?|\\.\\d+")) {
            throw new InvalidAmountException("'" + text.trim() + "' isn't a valid amount");
        }

        BigDecimal amount = Money.floor(new BigDecimal(s).multiply(multiplier));
        if (amount.signum() <= 0) throw new InvalidAmountException("The amount must be more than 0");
        if (amount.compareTo(Money.HARD_MAX) > 0) throw new InvalidAmountException("That amount is too large");
        return new AmountInput(amount, null);
    }

    public boolean isShare() {
        return fraction != null;
    }

    /** @return the amount given what's available (for shares), rounded down to the cent */
    public BigDecimal resolve(BigDecimal available) {
        if (fixed != null) return fixed;
        BigDecimal share = fraction == null ? BigDecimal.ONE : fraction;
        return Money.floor(available.max(BigDecimal.ZERO).multiply(share));
    }
}
