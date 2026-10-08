package dev.direk.dkbank.interest;

import dev.direk.dkbank.money.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterestMathTest {

    private static final long HOUR = 3_600_000L;
    private static final long DAY = 24 * HOUR;
    private static final BigDecimal ONE_PERCENT = BigDecimal.ONE;

    private static BigDecimal $(String value) {
        return new BigDecimal(value);
    }

    @Test
    void onlineHourPaysTheRate() {
        assertEquals($("100.00"), InterestMath.online($("10000"), ONE_PERCENT, HOUR, HOUR));
    }

    @Test
    void onlineIsProrated() {
        assertEquals($("25.00"), InterestMath.online($("10000"), ONE_PERCENT, HOUR / 4, HOUR)); // 15 minutes
    }

    @Test
    void offlineCompoundsLikeDailyPayouts() {
        // 3 days at 1%/day: 1.01^3 - 1 = 3.0301%
        assertEquals($("303.01"), InterestMath.compounding($("10000"), ONE_PERCENT, 3 * DAY, DAY));
        assertEquals($("100.00"), InterestMath.compounding($("10000"), ONE_PERCENT, DAY, DAY));
    }

    @Test
    void offlinePartsOfADayCount() {
        BigDecimal half = InterestMath.compounding($("10000"), ONE_PERCENT, DAY / 2, DAY);
        assertEquals($("49.87"), half); // 1.01^0.5 - 1 = 0.4987%
    }

    @Test
    void resultsRoundDown() {
        assertEquals($("0.00"), InterestMath.online($("0.99"), ONE_PERCENT, HOUR, HOUR)); // 0.0099
        assertEquals($("0.09"), InterestMath.online($("9.99"), ONE_PERCENT, HOUR, HOUR)); // 0.0999
    }

    @Test
    void nothingForNothing() {
        assertEquals(Money.ZERO, InterestMath.online(Money.ZERO, ONE_PERCENT, HOUR, HOUR));
        assertEquals(Money.ZERO, InterestMath.compounding($("100"), BigDecimal.ZERO, DAY, DAY));
        assertEquals(Money.ZERO, InterestMath.compounding($("100"), ONE_PERCENT, 0, DAY));
        assertEquals(Money.ZERO, InterestMath.online($("-50"), ONE_PERCENT, HOUR, HOUR));
    }

    @Test
    void capLimitsTheEarningBase() {
        assertEquals($("100000"), InterestMath.earningBase($("2500000"), $("100000")));
        assertEquals($("5000"), InterestMath.earningBase($("5000"), $("100000")));
        assertEquals($("2500000"), InterestMath.earningBase($("2500000"), null));
        assertEquals(BigDecimal.ZERO, InterestMath.earningBase($("-10"), null));
    }

    @Test
    void payoutLimits() {
        assertEquals($("500.00"), InterestMath.limit($("1234.56"), $("500"), $("99999")));  // max per payout
        assertEquals($("20.00"), InterestMath.limit($("1234.56"), null, $("20")));          // room under max balance
        assertEquals($("0.00"), InterestMath.limit($("1234.56"), null, $("-5")));           // already over: nothing
        assertEquals($("1234.56"), InterestMath.limit($("1234.56"), null, Money.HARD_MAX));
    }

    /**
     * One year of a player who plays 3 hours and is offline 21 hours a day, at 1% per hour online and
     * 1% per day offline. With a 100k cap the bank grows by a fixed amount a day (linear); without a cap
     * it snowballs. This is why every tier has a cap.
     */
    @Test
    void capKeepsAYearOfInterestLinear() {
        BigDecimal capped = simulateYear($("1000000"), $("100000"));
        BigDecimal uncapped = simulateYear($("1000000"), null);

        // Capped: interest is at most (3 h × 1% + 21/24 days at 1%) of 100k = about 3,875 a day.
        BigDecimal cappedGain = capped.subtract($("1000000"));
        assertTrue(cappedGain.compareTo($("1350000")) > 0 && cappedGain.compareTo($("1420000")) < 0,
                "capped yearly gain was " + cappedGain);

        // Uncapped: over 1,000 times the starting balance. Never ship without a cap.
        assertTrue(uncapped.compareTo($("1000000").multiply($("1000"))) > 0, "uncapped ended at " + uncapped);
    }

    private static BigDecimal simulateYear(BigDecimal start, BigDecimal cap) {
        BigDecimal balance = start;
        for (int day = 0; day < 365; day++) {
            for (int hour = 0; hour < 3; hour++) {
                balance = balance.add(InterestMath.online(InterestMath.earningBase(balance, cap), ONE_PERCENT, HOUR, HOUR));
            }
            balance = balance.add(InterestMath.compounding(InterestMath.earningBase(balance, cap), ONE_PERCENT, 21 * HOUR, DAY));
        }
        return balance;
    }
}
