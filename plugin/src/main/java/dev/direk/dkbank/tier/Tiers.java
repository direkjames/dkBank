package dev.direk.dkbank.tier;

import dev.direk.dkbank.interest.InterestPlan;
import dev.direk.dkbank.money.Money;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * All bank tiers, lowest first. The first tier is everyone's starting tier.
 * <p>
 * A player's tier is the highest of: the first tier, the tier they bought, and any tier they have the
 * {@code dkbank.tier.<id>} permission for.
 */
public final class Tiers {

    private static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,16}");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final List<Tier> list;
    private final Map<String, Tier> byId;
    private final Set<String> warnedUnknown = Collections.synchronizedSet(new HashSet<>());
    private final Logger log;

    private Tiers(List<Tier> list, Logger log) {
        this.list = List.copyOf(list);
        this.byId = new LinkedHashMap<>();
        for (Tier tier : list) byId.put(tier.id(), tier);
        this.log = log;
    }

    /**
     * Reads the {@code tiers} section of tiers.yml. Each value is a map of that tier's settings (nested
     * sections as maps). Problems are logged and replaced with safe values; if no tier is usable, a single
     * tier with the template's settings is used so the bank keeps working.
     *
     * @param template interest periods and switches from config.yml; each tier adds its rates and limits
     */
    public static Tiers parse(Map<String, ?> section, InterestPlan template, Logger log) {
        List<Tier> tiers = new ArrayList<>();
        for (Map.Entry<String, ?> entry : section.entrySet()) {
            String id = entry.getKey().toLowerCase(Locale.ROOT);
            String where = "tiers.yml → " + entry.getKey();
            if (!ID.matcher(id).matches()) {
                log.warning(where + ": tier names may only use a-z, 0-9, _ and -, up to 16 characters. Skipped.");
                continue;
            }
            if (tiers.stream().anyMatch(t -> t.id().equals(id))) {
                log.warning(where + ": listed twice. Skipped the second one.");
                continue;
            }
            if (!(entry.getValue() instanceof Map<?, ?> values)) {
                log.warning(where + ": isn't a section with settings. Skipped.");
                continue;
            }
            Reader r = new Reader(values, where, log);
            boolean first = tiers.isEmpty();
            BigDecimal cost = r.amount("upgrade-cost", "0");
            BigDecimal cap = r.amount("interest.cap", "0");
            InterestPlan plan = template.withTier(r.rate("interest.online-rate", "1"), r.rate("interest.offline-rate", "1"),
                    cap, r.amount("interest.max-per-payout", "0"));
            if (plan.cap() == null && (plan.onlineEnabled() || plan.offlineEnabled())) {
                log.warning(where + ": interest.cap is 0 (no cap). Big balances will snowball; a cap is strongly recommended.");
            }
            BigDecimal max = r.amount("max-balance", "0");
            tiers.add(new Tier(id, tiers.size(), r.text("display-name", entry.getKey()), r.text("icon", "GOLD_INGOT"),
                    first ? Money.ZERO : cost, first || r.bool("buyable", true),
                    max.signum() > 0 ? max : null, plan));
        }
        if (tiers.isEmpty()) {
            log.warning("tiers.yml has no usable tiers. Everyone gets one basic tier until it's fixed.");
            tiers.add(new Tier("basic", 0, "Basic", "PAPER", Money.ZERO, true, null,
                    template.withTier(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.valueOf(100_000), null)));
        }
        return new Tiers(tiers, log);
    }

    /** Everyone's starting tier: the first one in tiers.yml. */
    public Tier first() {
        return list.getFirst();
    }

    /** All tiers, lowest first. */
    public List<Tier> all() {
        return list;
    }

    public Optional<Tier> byId(@Nullable String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id.toLowerCase(Locale.ROOT)));
    }

    /** The tier stored for an account; a tier removed from tiers.yml counts as the first tier. */
    public Tier bought(@Nullable String id) {
        if (id == null || id.isEmpty()) return first();
        Tier tier = byId.get(id);
        if (tier != null) return tier;
        if (warnedUnknown.add(id)) {
            log.warning("Some accounts have the tier '" + id + "', which isn't in tiers.yml anymore. "
                    + "They count as " + first().id() + " until it's added back or changed with /bank admin tier.");
        }
        return first();
    }

    /**
     * A player's tier: the highest of their bought tier and every tier they have the permission for.
     *
     * @param hasPermission checks a permission, e.g. {@code player::hasPermission}
     */
    public Tier resolve(@Nullable String boughtId, Predicate<String> hasPermission) {
        Tier best = bought(boughtId);
        for (int i = list.size() - 1; i > best.rank(); i--) {
            Tier tier = list.get(i);
            if (hasPermission.test(tier.permission())) return tier;
        }
        return best;
    }

    /** The cheapest step up: the first buyable tier above {@code current}, if any. */
    public Optional<Tier> nextBuyable(Tier current) {
        for (Tier tier : list) {
            if (tier.isAbove(current) && tier.buyable()) return Optional.of(tier);
        }
        return Optional.empty();
    }

    /** Values of one tier, read with logging and fallbacks. */
    private record Reader(Map<?, ?> values, String where, Logger log) {

        @Nullable Object get(String path) {
            Object current = values;
            for (String part : path.split("\\.")) {
                if (!(current instanceof Map<?, ?> map)) return null;
                current = map.get(part);
            }
            return current;
        }

        String text(String path, String fallback) {
            Object value = get(path);
            return value == null ? fallback : String.valueOf(value);
        }

        boolean bool(String path, boolean fallback) {
            Object value = get(path);
            if (value instanceof Boolean b) return b;
            if (value != null) log.warning(where + "." + path + " must be true or false. Using " + fallback + ".");
            return fallback;
        }

        BigDecimal amount(String path, String fallback) {
            Object value = get(path);
            String text = value == null ? fallback : String.valueOf(value).trim();
            try {
                BigDecimal amount = Money.floor(new BigDecimal(text));
                if (amount.signum() < 0) throw new NumberFormatException();
                return amount.min(Money.HARD_MAX);
            } catch (NumberFormatException e) {
                log.warning(where + "." + path + " must be 0 or more, not '" + text + "'. Using " + fallback + ".");
                return Money.floor(new BigDecimal(fallback));
            }
        }

        BigDecimal rate(String path, String fallback) {
            Object value = get(path);
            String text = value == null ? fallback : String.valueOf(value).trim();
            try {
                BigDecimal rate = new BigDecimal(text);
                if (rate.signum() < 0 || rate.compareTo(HUNDRED) > 0) throw new NumberFormatException();
                return rate.stripTrailingZeros();
            } catch (NumberFormatException e) {
                log.warning(where + "." + path + " must be a percentage from 0 to 100, not '" + text + "'. Using " + fallback + ".");
                return new BigDecimal(fallback);
            }
        }
    }
}
