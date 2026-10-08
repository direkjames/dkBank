package dev.direk.dkbank.config;

import dev.direk.dkbank.interest.InterestPlan;
import dev.direk.dkbank.money.Money;
import dev.direk.dkbank.money.MoneyFormat;
import dev.direk.dkbank.util.TimeText;
import net.kyori.adventure.key.Key;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.logging.Logger;

/**
 * Everything in config.yml, read and checked once per load. Bad values are reported in the console and
 * replaced with safe defaults, so a typo never stops the plugin.
 */
public record Settings(
        List<String> aliases,
        MoneyFormat format,
        boolean useEconomyFormat,
        BigDecimal minAmount,
        BigDecimal maxPerTransaction,
        BigDecimal withdrawFeePercent,
        BigDecimal transferFeePercent,
        boolean transfersEnabled,
        boolean offlineTransfers,
        int historyPageSize,
        DateTimeFormatter dateFormat,
        Interest interest,
        AfkDetection afk,
        Menus menus,
        Alts alts,
        Storage storage
) {

    /**
     * @param maxPerAddress  accounts from one connection that can use the bank
     * @param windowMillis   logins older than this don't count
     * @param allowWithdraw  locked accounts can still take their money out
     * @param tellPlayer     tell locked players when they join
     */
    public record Alts(boolean enabled, int maxPerAddress, long windowMillis, boolean allowWithdraw, boolean tellPlayer) {
    }

    /**
     * @param openFromCommands    /bank and its commands without arguments open menus instead of using chat
     * @param inputSeconds        time to type an amount or name in chat
     * @param cancelWord          typing this cancels
     */
    public record Menus(boolean openFromCommands, int inputSeconds, String cancelWord) {
    }

    /**
     * @param template        periods and on/off switches; each tier in tiers.yml adds its rates and limits
     * @param loginDelayTicks delay before the "while you were away" message
     * @param sound           payout sound, or null for none
     */
    public record Interest(InterestPlan template, boolean notifyOnline, boolean notifyOffline, long loginDelayTicks,
                           @Nullable Key sound) {
    }

    /**
     * @param idleAfterMillis idle time after which a player counts as AFK; 0 = don't use idle time
     * @param afkValues       placeholder results meaning AFK, lower case
     */
    public record AfkDetection(boolean enabled, long idleAfterMillis, List<String> placeholders, Set<String> afkValues) {
    }

    public enum StorageType { SQLITE, MYSQL }

    public record Storage(StorageType type, String sqliteFile, String host, int port, String database,
                          String username, String password, boolean useSsl, int poolSize, String tablePrefix) {
    }

    public static Settings load(YamlConfiguration c, Logger log) {
        Checker check = new Checker(log);

        ConfigurationSection cur = c.getConfigurationSection("currency");
        String[] suffixes = c.getStringList("currency.compact-suffixes").toArray(String[]::new);
        if (suffixes.length != 5) {
            log.warning("currency.compact-suffixes needs 5 entries (thousand, million, billion, trillion, quadrillion). Using K, M, B, T, Q.");
            suffixes = new String[]{"K", "M", "B", "T", "Q"};
        }
        MoneyFormat format = new MoneyFormat(
                c.getString("currency.symbol", "$"),
                "after".equalsIgnoreCase(c.getString("currency.symbol-position", "before")),
                c.getBoolean("currency.show-cents", true),
                c.getString("currency.thousands-separator", ","),
                c.getString("currency.decimal-separator", "."),
                suffixes);

        String zone = c.getString("history.timezone", "");
        ZoneId zoneId;
        try {
            zoneId = zone == null || zone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(zone);
        } catch (Exception e) {
            log.warning("Unknown history.timezone '" + zone + "'. Using the server's timezone.");
            zoneId = ZoneId.systemDefault();
        }
        DateTimeFormatter dates;
        String pattern = c.getString("history.date-format", "MMM d, HH:mm");
        try {
            dates = DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH).withZone(zoneId);
        } catch (IllegalArgumentException e) {
            log.warning("Invalid history.date-format '" + pattern + "'. Using MMM d, HH:mm.");
            dates = DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.ENGLISH).withZone(zoneId);
        }

        String type = c.getString("storage.type", "sqlite").toLowerCase(Locale.ROOT);
        StorageType storageType = switch (type) {
            case "mysql", "mariadb" -> StorageType.MYSQL;
            case "sqlite" -> StorageType.SQLITE;
            default -> {
                log.warning("Unknown storage.type '" + type + "'. Using sqlite.");
                yield StorageType.SQLITE;
            }
        };
        String prefix = c.getString("storage.table-prefix", "dkbank_");
        if (!prefix.matches("[A-Za-z0-9_]*")) {
            log.warning("storage.table-prefix may only use letters, numbers and _. Using dkbank_.");
            prefix = "dkbank_";
        }
        Storage storage = new Storage(storageType,
                c.getString("storage.sqlite.file", "bank.db"),
                c.getString("storage.mysql.host", "localhost"),
                c.getInt("storage.mysql.port", 3306),
                c.getString("storage.mysql.database", "dkbank"),
                c.getString("storage.mysql.username", "root"),
                c.getString("storage.mysql.password", ""),
                c.getBoolean("storage.mysql.use-ssl", false),
                Math.max(2, Math.min(20, c.getInt("storage.mysql.pool-size", 6))),
                prefix);

        // Rates, caps and limits are per tier (tiers.yml).
        InterestPlan template = new InterestPlan(
                c.getBoolean("interest.online.enabled", true),
                BigDecimal.ZERO,
                check.duration(c, "interest.online.period", "1h", Duration.ofMinutes(1)),
                c.getBoolean("interest.offline.enabled", true),
                BigDecimal.ZERO,
                check.duration(c, "interest.offline.period", "1d", Duration.ofMinutes(1)),
                check.duration(c, "interest.offline.max-time", "7d", Duration.ZERO),
                null, null);
        Key sound = null;
        String soundName = c.getString("interest.notify.sound", "");
        if (soundName != null && !soundName.isBlank()) {
            String key = soundName.trim().toLowerCase(Locale.ROOT);
            if (Key.parseable(key)) sound = Key.key(key);
            else log.warning("interest.notify.sound '" + soundName + "' isn't a sound name. No sound will play.");
        }
        Interest interest = new Interest(template,
                c.getBoolean("interest.notify.online-payout", true),
                c.getBoolean("interest.notify.offline-payout", true),
                Math.max(0, Math.min(60, c.getInt("interest.notify.login-delay", 3))) * 20L,
                sound);

        AfkDetection afk = new AfkDetection(
                c.getBoolean("afk-detection.enabled", true),
                check.duration(c, "afk-detection.idle-after", "5m", Duration.ZERO),
                c.getStringList("afk-detection.placeholders").stream().filter(t -> !t.isBlank()).map(String::trim).toList(),
                c.getStringList("afk-detection.afk-values").stream().map(v -> v.trim().toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet()));

        return new Settings(
                List.copyOf(c.getStringList("command.aliases")),
                format,
                cur != null && cur.getBoolean("use-economy-format", false),
                check.amount(c, "limits.min-amount", "1", false),
                check.amount(c, "limits.max-per-transaction", "0", true),
                check.percent(c, "fees.withdraw-percent"),
                check.percent(c, "fees.transfer-percent"),
                c.getBoolean("transfers.enabled", true),
                c.getBoolean("transfers.allow-offline-players", true),
                Math.max(3, Math.min(20, c.getInt("history.page-size", 8))),
                dates,
                interest,
                afk,
                new Menus(c.getBoolean("menus.open-from-commands", true),
                        Math.max(5, Math.min(300, c.getInt("menus.chat-input-seconds", 30))),
                        c.getString("menus.cancel-word", "cancel").trim()),
                new Alts(c.getBoolean("alt-limit.enabled", true),
                        Math.max(1, c.getInt("alt-limit.max-accounts", 3)),
                        check.duration(c, "alt-limit.remember", "30d", Duration.ofDays(1)),
                        c.getBoolean("alt-limit.locked-can-withdraw", true),
                        c.getBoolean("alt-limit.tell-player", true)),
                storage);
    }

    /** @return true if the amount is above the per-transaction limit (0 means no limit) */
    public boolean overTransactionLimit(BigDecimal amount) {
        return maxPerTransaction.signum() > 0 && amount.compareTo(maxPerTransaction) > 0;
    }

    private record Checker(Logger log) {

        BigDecimal amount(YamlConfiguration c, String path, String fallback, boolean zeroAllowed) {
            String text = String.valueOf(c.get(path, fallback));
            try {
                BigDecimal value = Money.floor(new BigDecimal(text.trim()));
                if (value.signum() < 0 || (!zeroAllowed && value.signum() == 0)) throw new NumberFormatException();
                return value.min(Money.HARD_MAX);
            } catch (NumberFormatException e) {
                log.warning(path + " must be " + (zeroAllowed ? "0 or more" : "more than 0") + ", not '" + text + "'. Using " + fallback + ".");
                return Money.floor(new BigDecimal(fallback));
            }
        }

        /** A duration like 1h or 7d, at least {@code min}. Returns milliseconds. */
        long duration(YamlConfiguration c, String path, String fallback, Duration min) {
            String text = String.valueOf(c.get(path, fallback));
            try {
                Duration value = TimeText.parse(text);
                if (value.compareTo(min) < 0) {
                    log.warning(path + " must be at least " + TimeText.format(min) + ", not '" + text + "'. Using " + fallback + ".");
                    return TimeText.parse(fallback).toMillis();
                }
                return value.toMillis();
            } catch (IllegalArgumentException e) {
                log.warning(path + ": " + e.getMessage() + ". Using " + fallback + ".");
                return TimeText.parse(fallback).toMillis();
            }
        }

        BigDecimal percent(YamlConfiguration c, String path) {
            String text = String.valueOf(c.get(path, "0"));
            try {
                BigDecimal value = new BigDecimal(text.trim());
                if (value.signum() < 0 || value.compareTo(BigDecimal.valueOf(100)) >= 0) throw new NumberFormatException();
                return value;
            } catch (NumberFormatException e) {
                log.warning(path + " must be a percentage from 0 to 99.99, not '" + text + "'. Using 0.");
                return BigDecimal.ZERO;
            }
        }
    }
}
