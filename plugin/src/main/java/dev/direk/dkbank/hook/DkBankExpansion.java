package dev.direk.dkbank.hook;

import dev.direk.dkbank.DkBankPlugin;
import dev.direk.dkbank.bank.BankService;
import dev.direk.dkbank.bank.Leaderboard;
import dev.direk.dkbank.storage.StoreTypes.TopEntry;
import dev.direk.dkbank.tier.Tier;
import dev.direk.dkbank.util.TimeText;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code %dkbank_...%} placeholders. Everything comes from memory (cached balances, tiers, the last
 * leaderboard), never the database, so scoreboards and tab lists can ask as often as they like, from any
 * thread. Only loaded when PlaceholderAPI is installed.
 */
public final class DkBankExpansion extends PlaceholderExpansion {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('§').hexColors().useUnusualXRepeatedCharacterHexFormat().build();

    /** For the docs and /papi info. */
    public static final List<String> PLACEHOLDERS = List.of(
            "balance", "balance_short", "balance_full", "balance_raw", "max_balance", "room",
            "tier", "tier_plain", "tier_id", "next_tier", "next_tier_cost",
            "online_rate", "offline_rate", "cap", "next_payout", "next_payout_seconds", "locked",
            "rank", "top_name_<place>", "top_balance_<place>", "top_balance_short_<place>",
            "total", "total_short", "accounts");

    private final DkBankPlugin plugin;

    public DkBankExpansion(DkBankPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "dkbank";
    }

    @Override
    public String getAuthor() {
        return "Direk";
    }

    @Override
    public String getVersion() {
        return plugin.version();
    }

    @Override
    public boolean persist() {
        return true; // survives /papi reload
    }

    @Override
    public List<String> getPlaceholders() {
        return PLACEHOLDERS.stream().map(p -> "%dkbank_" + p + "%").toList();
    }

    @Override
    public @Nullable String onRequest(@Nullable OfflinePlayer player, String params) {
        if (!plugin.isEnabled()) return "";
        BankService bank = plugin.bank();
        Leaderboard.Snapshot top = plugin.leaderboard().snapshot();
        String p = params.toLowerCase(Locale.ROOT);

        // Server-wide values
        switch (p) {
            case "total" -> {
                return bank.fmtAnyThread(top.totals().balance());
            }
            case "total_short" -> {
                return bank.fmtShort(top.totals().balance());
            }
            case "accounts" -> {
                return String.valueOf(top.totals().accounts());
            }
            default -> {
            }
        }
        if (p.startsWith("top_")) return topValue(bank, top, p);

        if (player == null) return "";
        UUID uuid = player.getUniqueId();
        BigDecimal balance = bank.cachedBalance(uuid);
        if (balance == null) {
            // Offline: only the leaderboard knows their balance.
            int rank = top.rank(uuid);
            TopEntry entry = rank > 0 ? top.at(rank) : null;
            balance = entry == null ? null : entry.balance();
        }
        Tier tier = bank.tierFor(uuid);
        Player online = player.getPlayer();

        return switch (p) {
            case "balance" -> balance == null ? "" : bank.fmtAnyThread(balance);
            case "balance_short" -> balance == null ? "" : bank.fmtShort(balance);
            case "balance_full" -> balance == null ? "" : bank.settings().format().format(balance);
            case "balance_raw" -> balance == null ? "" : balance.toPlainString();
            case "max_balance" -> tier.maxBalance() == null ? text("tiers.no-limit") : bank.fmtAnyThread(tier.maxBalance());
            case "room" -> tier.maxBalance() == null || balance == null ? text("tiers.no-limit")
                    : bank.fmtAnyThread(tier.maxBalance().subtract(balance).max(BigDecimal.ZERO));
            case "tier" -> LEGACY.serialize(MiniMessage.miniMessage().deserialize(tier.displayName()));
            case "tier_plain" -> PlainTextComponentSerializer.plainText().serialize(MiniMessage.miniMessage().deserialize(tier.displayName()));
            case "tier_id" -> tier.id();
            case "next_tier" -> bank.tiers().nextBuyable(tier)
                    .map(t -> LEGACY.serialize(MiniMessage.miniMessage().deserialize(t.displayName()))).orElse("");
            case "next_tier_cost" -> bank.tiers().nextBuyable(tier).map(t -> bank.fmtAnyThread(t.cost())).orElse("");
            case "online_rate" -> tier.plan().onlineRate().toPlainString();
            case "offline_rate" -> tier.plan().offlineRate().toPlainString();
            case "cap" -> tier.plan().cap() == null ? text("tiers.no-limit") : bank.fmtAnyThread(tier.plan().cap());
            case "next_payout" -> {
                long until = plugin.interest().untilPayout(uuid, tier.plan().onlinePeriodMillis());
                yield until < 0 ? "" : TimeText.format(Duration.ofMillis(until));
            }
            case "next_payout_seconds" -> {
                long until = plugin.interest().untilPayout(uuid, tier.plan().onlinePeriodMillis());
                yield until < 0 ? "" : String.valueOf(until / 1000);
            }
            case "locked" -> String.valueOf(online != null && bank.alts().isLockedCached(uuid)); // no permission checks off-thread
            case "rank" -> {
                int rank = top.rank(uuid);
                yield rank == 0 ? "-" : String.valueOf(rank);
            }
            default -> null; // unknown placeholder: PlaceholderAPI leaves it as typed
        };
    }

    /** {@code top_name_1}, {@code top_balance_1}, {@code top_balance_short_1}. */
    private static @Nullable String topValue(BankService bank, Leaderboard.Snapshot top, String p) {
        int underscore = p.lastIndexOf('_');
        int place;
        try {
            place = Integer.parseInt(p.substring(underscore + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        String kind = p.substring(0, underscore);
        Optional<TopEntry> entry = Optional.ofNullable(top.at(place));
        return switch (kind) {
            case "top_name" -> entry.map(TopEntry::name).orElse("-");
            case "top_balance" -> entry.map(e -> bank.fmtAnyThread(e.balance())).orElse("-");
            case "top_balance_short" -> entry.map(e -> bank.fmtShort(e.balance())).orElse("-");
            default -> null;
        };
    }

    private String text(String key) {
        return plugin.bank().messages().raw(key);
    }
}
