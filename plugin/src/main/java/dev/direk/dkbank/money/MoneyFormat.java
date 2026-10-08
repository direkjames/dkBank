package dev.direk.dkbank.money;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Turns amounts into text: {@code $1,234.56} in full, or {@code $1.2M} compact.
 *
 * @param symbol            currency symbol, e.g. {@code $}
 * @param symbolAfter       true for {@code 100$}, false for {@code $100}
 * @param showCents         false hides the decimals ({@code $1,235} is never shown: cents are cut, not rounded up)
 * @param thousands         thousands separator, e.g. {@code ,} ({@code ""} for none)
 * @param decimal           decimal separator, e.g. {@code .}
 * @param compactSuffixes   suffixes for thousands, millions, billions, trillions, quadrillions
 * @param compactDecimals   decimals in short amounts: 1 gives {@code $1.2M}, 2 gives {@code $1.25M}
 * @param shortFrom         amounts this big or bigger are shown short everywhere; null for never
 */
public record MoneyFormat(String symbol, boolean symbolAfter, boolean showCents, String thousands,
                          String decimal, String[] compactSuffixes, int compactDecimals,
                          @Nullable BigDecimal shortFrom) {

    public MoneyFormat {
        compactDecimals = Math.max(0, Math.min(2, compactDecimals));
    }

    /** One decimal in short amounts, and never short unless asked. */
    public MoneyFormat(String symbol, boolean symbolAfter, boolean showCents, String thousands, String decimal,
                       String[] compactSuffixes) {
        this(symbol, symbolAfter, showCents, thousands, decimal, compactSuffixes, 1, null);
    }

    /** The way amounts are shown in messages and menus: in full, or short from {@link #shortFrom()} up. */
    public String display(BigDecimal amount) {
        return shortFrom != null && amount.abs().compareTo(shortFrom) >= 0 ? compact(amount) : format(amount);
    }

    public static final MoneyFormat DEFAULT = new MoneyFormat("$", false, true, ",", ".",
            new String[]{"K", "M", "B", "T", "Q"});

    /** {@code $1,234.56} */
    public String format(BigDecimal amount) {
        return withSymbol(number(amount));
    }

    /** The number without the symbol: {@code 1,234.56} */
    public String number(BigDecimal amount) {
        BigDecimal value = amount.setScale(showCents ? Money.SCALE : 0, RoundingMode.DOWN);
        boolean negative = value.signum() < 0;
        String plain = value.abs().toPlainString();
        int dot = plain.indexOf('.');
        String whole = dot < 0 ? plain : plain.substring(0, dot);
        String cents = dot < 0 ? "" : plain.substring(dot + 1);

        StringBuilder sb = new StringBuilder();
        int firstGroup = whole.length() % 3 == 0 ? 3 : whole.length() % 3;
        sb.append(whole, 0, firstGroup);
        for (int i = firstGroup; i < whole.length(); i += 3) {
            sb.append(thousands).append(whole, i, i + 3);
        }
        if (!cents.isEmpty()) sb.append(decimal).append(cents);
        return (negative ? "-" : "") + sb;
    }

    /** {@code $1.2M}, {@code $950}, {@code $12.5K}. {@link #compactDecimals()} decimals at most, rounded down. */
    public String compact(BigDecimal amount) {
        BigDecimal abs = amount.abs();
        BigDecimal thousand = BigDecimal.valueOf(1000);
        int tier = -1;
        BigDecimal scaled = abs;
        while (scaled.compareTo(thousand) >= 0 && tier < compactSuffixes.length - 1) {
            scaled = scaled.divide(thousand);
            tier++;
        }
        if (tier < 0) {
            return withSymbol(amount.setScale(0, RoundingMode.DOWN).toPlainString()); // under 1,000: no cents
        }
        BigDecimal shown = scaled.setScale(compactDecimals, RoundingMode.DOWN).stripTrailingZeros();
        String text = shown.toPlainString().replace(".", decimal) + compactSuffixes[tier];
        return withSymbol((amount.signum() < 0 ? "-" : "") + text);
    }

    /** Adds the symbol, keeping a minus sign in front: {@code -$5.00}, not {@code $-5.00}. */
    private String withSymbol(String number) {
        boolean negative = number.startsWith("-");
        String digits = negative ? number.substring(1) : number;
        return (negative ? "-" : "") + (symbolAfter ? digits + symbol : symbol + digits);
    }
}
