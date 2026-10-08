package dev.direk.dkbank.tier;

import dev.direk.dkbank.interest.InterestPlan;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TiersTest {

    private static final InterestPlan TEMPLATE = new InterestPlan(true, BigDecimal.ZERO, 3_600_000L,
            true, BigDecimal.ZERO, 86_400_000L, 7 * 86_400_000L, null, null);

    private final List<String> warnings = new ArrayList<>();
    private final Logger log = Logger.getAnonymousLogger();

    {
        log.setUseParentHandlers(false);
        log.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                warnings.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
    }

    private static BigDecimal $(String value) {
        return new BigDecimal(value);
    }

    private static Map<String, Object> tier(Object cost, Object max, Object cap, Object... extra) {
        Map<String, Object> interest = new LinkedHashMap<>();
        interest.put("online-rate", 1);
        interest.put("offline-rate", 1);
        interest.put("cap", cap);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("upgrade-cost", cost);
        values.put("max-balance", max);
        values.put("interest", interest);
        for (int i = 0; i + 1 < extra.length; i += 2) values.put((String) extra[i], extra[i + 1]);
        return values;
    }

    /** basic, silver, gold (buyable), vip (permission only), legend (buyable). */
    private Tiers sample() {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("basic", tier(999, 250000, 25000));
        section.put("silver", tier(25000, 1000000, 100000, "display-name", "<white>Silver"));
        section.put("Gold", tier("150k?", 0, 500000)); // bad cost, upper-case name
        section.put("vip", tier(0, 0, 1000000, "buyable", false));
        section.put("legend", tier(9000000, 0, 5000000));
        return Tiers.parse(section, TEMPLATE, log);
    }

    private static Set<String> perms(String... tiers) {
        Set<String> set = new java.util.HashSet<>();
        for (String t : tiers) set.add(Tier.PERMISSION_PREFIX + t);
        return set;
    }

    @Test
    void readsTiersInOrder() {
        Tiers tiers = sample();
        assertEquals(List.of("basic", "silver", "gold", "vip", "legend"), tiers.all().stream().map(Tier::id).toList());
        Tier silver = tiers.byId("SILVER").orElseThrow();
        assertEquals(1, silver.rank());
        assertEquals("<white>Silver", silver.displayName());
        assertEquals($("25000.00"), silver.cost());
        assertEquals($("1000000.00"), silver.maxBalance());
        assertEquals($("100000.00"), silver.plan().cap());
        assertEquals(0, BigDecimal.ONE.compareTo(silver.plan().onlineRate()));
        assertEquals(3_600_000L, silver.plan().onlinePeriodMillis(), "periods come from config.yml");
        assertEquals("dkbank.tier.silver", silver.permission());
    }

    @Test
    void firstTierIsFreeAndBuyable() {
        Tier basic = sample().first();
        assertEquals($("0.00"), basic.cost());
        assertTrue(basic.buyable());
    }

    @Test
    void badValuesFallBackWithAWarning() {
        Tiers tiers = sample();
        Tier gold = tiers.byId("gold").orElseThrow();
        assertEquals($("0.00"), gold.cost());
        assertEquals(null, gold.maxBalance(), "0 means no limit");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("upgrade-cost")), "warned about the cost: " + warnings);
    }

    @Test
    void permissionTierWinsWhenHigher() {
        Tiers tiers = sample();
        assertEquals("basic", tiers.resolve(null, p -> false).id());
        assertEquals("silver", tiers.resolve("silver", p -> false).id());
        assertEquals("vip", tiers.resolve("silver", perms("vip")::contains).id());
        assertEquals("legend", tiers.resolve("legend", perms("vip")::contains).id(), "bought tier is higher");
        assertEquals("legend", tiers.resolve(null, perms("silver", "legend")::contains).id(), "highest permission wins");
    }

    @Test
    void removedTierCountsAsTheFirst() {
        Tiers tiers = sample();
        assertEquals("basic", tiers.resolve("platinum", p -> false).id());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("platinum")));
    }

    @Test
    void nextUpgradeSkipsPermissionOnlyTiers() {
        Tiers tiers = sample();
        assertEquals("silver", tiers.nextBuyable(tiers.first()).orElseThrow().id());
        assertEquals("legend", tiers.nextBuyable(tiers.byId("gold").orElseThrow()).orElseThrow().id());
        assertEquals("legend", tiers.nextBuyable(tiers.byId("vip").orElseThrow()).orElseThrow().id());
        assertFalse(tiers.nextBuyable(tiers.byId("legend").orElseThrow()).isPresent());
    }

    @Test
    void invalidTiersAreSkipped() {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("has space", tier(0, 0, 1000));
        section.put("broken", "not a section");
        section.put("ok", tier(0, 0, 1000));
        Tiers tiers = Tiers.parse(section, TEMPLATE, log);
        assertEquals(List.of("ok"), tiers.all().stream().map(Tier::id).toList());
    }

    @Test
    void typosAreReported() {
        Map<String, Object> values = tier(100, 0, 1000, "upgrade-price", 5);
        @SuppressWarnings("unchecked") Map<String, Object> interest = (Map<String, Object>) values.get("interest");
        interest.put("onlin-rate", 2);
        Tiers.parse(Map.of("silver", values), TEMPLATE, log);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("upgrade-price")), String.valueOf(warnings));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("onlin-rate")), String.valueOf(warnings));
    }

    @Test
    void decimalRates() {
        Map<String, Object> interest = new LinkedHashMap<>();
        interest.put("online-rate", 0.5);    // YAML numbers arrive as doubles
        interest.put("offline-rate", "1.50");
        interest.put("cap", 10000);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("interest", interest);
        Tier tier = Tiers.parse(Map.of("half", values), TEMPLATE, log).first();
        assertEquals("0.5", tier.plan().onlineRate().toPlainString());
        assertEquals("1.5", tier.plan().offlineRate().toPlainString());
        assertTrue(warnings.isEmpty(), "no warnings: " + warnings);

        Map<String, Object> ten = new LinkedHashMap<>(interest);
        ten.put("online-rate", 10);
        values.put("interest", ten);
        assertEquals("10", Tiers.parse(Map.of("ten", values), TEMPLATE, log).first().plan().onlineRate().toPlainString());
    }

    @Test
    void noTiersStillWorks() {
        Tiers tiers = Tiers.parse(Map.of(), TEMPLATE, log);
        assertEquals(1, tiers.all().size());
        assertTrue(tiers.first().plan().cap() != null);
    }
}
