package dev.direk.dkbank.money;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoneyTest {

    private static BigDecimal $(String value) {
        return new BigDecimal(value);
    }

    private static BigDecimal parse(String text) throws AmountInput.InvalidAmountException {
        return AmountInput.parse(text).resolve(BigDecimal.ZERO);
    }

    // ------------------------------------------------------------------ parsing

    @Test
    void parsesPlainAmounts() throws Exception {
        assertEquals($("1000.00"), parse("1000"));
        assertEquals($("1000.00"), parse("1,000"));
        assertEquals($("2500.75"), parse("2500.75"));
        assertEquals($("0.50"), parse(".5"));
    }

    @Test
    void parsesSuffixes() throws Exception {
        assertEquals($("1500.00"), parse("1.5k"));
        assertEquals($("2000000.00"), parse("2M"));
        assertEquals($("1000000000.00"), parse("1b"));
        assertEquals($("3000000000000.00"), parse("3t"));
        assertEquals($("1234.50"), parse("1.2345k"));
    }

    @Test
    void extraDecimalsAreRoundedDownNeverUp() throws Exception {
        assertEquals($("10.99"), parse("10.999"));
        assertEquals($("0.01"), parse("0.019"));
    }

    @Test
    void sharesResolveAgainstWhatsAvailable() throws Exception {
        assertTrue(AmountInput.parse("all").isShare());
        assertEquals($("1234.56"), AmountInput.parse("all").resolve($("1234.56")));
        assertEquals($("617.28"), AmountInput.parse("HALF").resolve($("1234.56")));
        assertEquals($("0.50"), AmountInput.parse("half").resolve($("1.01"))); // 0.505 rounds down
        assertEquals($("0.00"), AmountInput.parse("all").resolve($("-5")));
    }

    @Test
    void rejectsBadAmounts() {
        for (String bad : new String[]{"", "abc", "-5", "0", "0.001", "1e5", "5kk", "1.2.3", "k", "10%"}) {
            assertThrows(AmountInput.InvalidAmountException.class, () -> AmountInput.parse(bad), bad);
        }
        assertThrows(AmountInput.InvalidAmountException.class, () -> AmountInput.parse("2000t")); // over the hard maximum
    }

    // ------------------------------------------------------------------ cents and fees

    @Test
    void centsRoundTrip() {
        assertEquals(123456L, Money.toCents($("1234.56")));
        assertEquals($("1234.56"), Money.fromCents(123456L));
        assertEquals(Money.toCents(Money.HARD_MAX), 100_000_000_000_000_000L);
    }

    @Test
    void doublesFromEconomyPluginsAreRoundedDown() {
        assertEquals($("0.30"), Money.fromDouble(0.1 + 0.2)); // 0.30000000000000004
        assertEquals($("19.99"), Money.fromDouble(19.999));
        assertEquals(Money.ZERO, Money.fromDouble(Double.NaN));
    }

    @Test
    void feesRoundUp() {
        assertEquals($("2.50"), Money.fee($("100"), $("2.5")));
        assertEquals($("0.01"), Money.fee($("0.10"), $("1")));   // 0.001 rounds up to a cent
        assertEquals(Money.ZERO, Money.fee($("100"), BigDecimal.ZERO));
    }

    @Test
    void largestAmountThatFitsWithItsFee() {
        BigDecimal amount = Money.largestWithFee($("1000"), $("2.5"));
        assertTrue(amount.add(Money.fee(amount, $("2.5"))).compareTo($("1000")) <= 0);
        BigDecimal oneMore = amount.add($("0.01"));
        assertTrue(oneMore.add(Money.fee(oneMore, $("2.5"))).compareTo($("1000")) > 0, "should be the largest");
        assertEquals($("1000.00"), Money.largestWithFee($("1000"), BigDecimal.ZERO));
    }

    // ------------------------------------------------------------------ formatting

    @Test
    void formatsFullAmounts() {
        MoneyFormat f = MoneyFormat.DEFAULT;
        assertEquals("$1,234,567.89", f.format($("1234567.89")));
        assertEquals("$0.00", f.format($("0")));
        assertEquals("$999.00", f.format($("999")));
        assertEquals("-$5.00", f.format($("-5")));
    }

    @Test
    void formatsWithOtherSettings() {
        MoneyFormat euro = new MoneyFormat(" €", true, true, ".", ",", new String[]{"k", "M", "B", "T", "Q"});
        assertEquals("1.234,50 €", euro.format($("1234.5")));
        MoneyFormat noCents = new MoneyFormat("$", false, false, ",", ".", new String[]{"K", "M", "B", "T", "Q"});
        assertEquals("$1,234", noCents.format($("1234.99"))); // cut, not rounded up
    }

    @Test
    void formatsCompact() {
        MoneyFormat f = MoneyFormat.DEFAULT;
        assertEquals("$950", f.compact($("950.75")));
        assertEquals("$12.5K", f.compact($("12599")));
        assertEquals("$1M", f.compact($("1000000")));
        assertEquals("$2.7B", f.compact($("2799999999")));
        assertEquals("-$1.5K", f.compact($("-1500")));
    }
}
