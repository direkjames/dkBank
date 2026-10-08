package dev.direk.dkbank.bank;

import dev.direk.dkbank.config.Messages;
import dev.direk.dkbank.interest.InterestPlan;
import dev.direk.dkbank.storage.StoreTypes.Account;
import dev.direk.dkbank.storage.StoreTypes.Failure;
import dev.direk.dkbank.storage.StoreTypes.Result;
import dev.direk.dkbank.tier.Tier;
import dev.direk.dkbank.tier.Tiers;
import dev.direk.dkbank.util.TimeText;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static dev.direk.dkbank.bank.BankService.vars;

/**
 * Bank tiers: the list, upgrades (offer, then confirm) and admin changes. Main thread, with the
 * database work on the bank's worker threads.
 */
public final class TierService {

    /** How long an upgrade offer can be confirmed. */
    private static final long CONFIRM_MILLIS = 30_000L;

    private final BankService bank;
    /** Upgrade offers waiting for confirmation. Main thread only. */
    private final Map<UUID, Offer> offers = new HashMap<>();

    private record Offer(String tier, BigDecimal cost, @Nullable String expectedBought, long expires) {
    }

    public TierService(BankService bank) {
        this.bank = bank;
    }

    public void forget(UUID uuid) {
        offers.remove(uuid);
    }

    // ------------------------------------------------------------------ /bank tiers

    public void showTiers(Player player) {
        Messages m = bank.messages();
        Tiers tiers = bank.tiers();
        Tier current = bank.tierOf(player);
        Tier next = tiers.nextBuyable(current).orElse(null);
        m.send(player, "tiers.header");
        for (Tier tier : tiers.all()) {
            String key;
            if (tier.equals(current)) key = "tiers.current";
            else if (!tier.isAbove(current)) key = "tiers.below";
            else if (tier.equals(next)) key = "tiers.next";
            else if (tier.buyable()) key = "tiers.locked";
            else key = "tiers.rank-only";
            m.send(player, key, details(tier), Map.of("tier", tier.displayName()));
        }
        m.send(player, next == null ? "tiers.footer-max" : "tiers.footer");
    }

    // ------------------------------------------------------------------ /bank upgrade

    /** Shows the next tier and what it costs, and remembers the offer for {@link #confirm(Player)}. */
    public void offer(Player player) {
        Tier current = bank.tierOf(player);
        Optional<Tier> next = bank.tiers().nextBuyable(current);
        if (next.isEmpty()) {
            offers.remove(player.getUniqueId());
            bank.messages().send(player, "upgrade.max", Map.of(), Map.of("tier", current.displayName()));
            return;
        }
        Tier tier = next.get();
        offers.put(player.getUniqueId(), new Offer(tier.id(), tier.cost(), bank.boughtTier(player.getUniqueId()),
                System.currentTimeMillis() + CONFIRM_MILLIS));

        Messages m = bank.messages();
        Map<String, String> v = details(tier);
        BigDecimal balance = bank.cachedBalance(player.getUniqueId());
        v.put("balance", bank.fmt(balance == null ? BigDecimal.ZERO : balance));
        v.put("seconds", String.valueOf(CONFIRM_MILLIS / 1000));
        Map<String, String> names = Map.of("tier", tier.displayName(), "current", current.displayName());
        m.send(player, "upgrade.info", v, names);
        Component button = m.renderText(m.raw("upgrade.confirm-button"), v, names);
        if (!m.raw("upgrade.confirm-button").isEmpty()) {
            player.sendMessage(button.clickEvent(ClickEvent.runCommand("/bank upgrade confirm")));
        }
    }

    /** Buys the offered tier, if the offer is still valid; otherwise shows a fresh offer. */
    public void confirm(Player player) {
        UUID uuid = player.getUniqueId();
        Offer offer = offers.remove(uuid);
        Tier current = bank.tierOf(player);
        Tier next = bank.tiers().nextBuyable(current).orElse(null);
        if (offer == null || offer.expires() < System.currentTimeMillis() || next == null
                || !next.id().equals(offer.tier()) || next.cost().compareTo(offer.cost()) != 0) {
            if (offer != null) bank.send(player, "upgrade.expired");
            offer(player);
            return;
        }
        purchase(player, current, next, offer.expectedBought(), BankService.Outcome.NONE);
    }

    /**
     * Buys the next tier straight away, for menus that asked for confirmation themselves.
     *
     * @param tierId the tier the player confirmed, and {@code cost} the price they saw; if either changed
     *               (e.g. tiers.yml was reloaded), nothing is bought
     */
    public void buy(Player player, String tierId, BigDecimal cost, BankService.Outcome done) {
        Tier current = bank.tierOf(player);
        Tier next = bank.tiers().nextBuyable(current).orElse(null);
        if (next == null || !next.id().equals(tierId) || next.cost().compareTo(cost) != 0) {
            bank.send(player, "upgrade.changed");
            done.finish(false);
            return;
        }
        purchase(player, current, next, bank.boughtTier(player.getUniqueId()), done);
    }

    private void purchase(Player player, Tier current, Tier next, @Nullable String expectedBought,
                          BankService.Outcome done) {
        if (bank.isBusy(player)) {
            done.finish(false);
            return;
        }
        if (bank.locked(player)) {
            bank.sendLocked(player);
            done.finish(false);
            return;
        }
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        bank.markBusy(uuid);
        bank.async(() -> {
            bank.ensure(uuid, name);
            return bank.store().upgrade(uuid, expectedBought, next.id(), next.cost());
        }, result -> {
            bank.clearBusy(uuid);
            upgraded(player, next, current, result);
            done.finish(result.ok());
        }, error -> {
            bank.clearBusy(uuid);
            bank.fail(player, "upgrade", error);
            done.finish(false);
        });
    }

    private void upgraded(Player player, Tier tier, Tier previous, Result result) {
        UUID uuid = player.getUniqueId();
        Messages m = bank.messages();
        if (result.ok()) {
            bank.cacheBalance(uuid, result.balance());
            bank.cacheBoughtTier(uuid, tier.id());
            Map<String, String> v = details(tier);
            v.put("balance", bank.fmt(result.balance()));
            v.put("player", player.getName());
            Map<String, String> names = Map.of("tier", tier.displayName(), "current", previous.displayName());
            m.send(player, "upgrade.success", v, names);
            String broadcast = m.raw("upgrade.broadcast");
            if (!broadcast.isEmpty()) {
                Component text = m.renderText(broadcast, v, names);
                for (Player online : Bukkit.getOnlinePlayers()) {
                    Audience audience = online;
                    if (!online.equals(player)) audience.sendMessage(text);
                }
            }
            return;
        }
        if (result.failure() == Failure.INSUFFICIENT_FUNDS) {
            m.send(player, "upgrade.not-enough", vars("cost", bank.fmt(tier.cost()), "balance", bank.fmt(result.available())),
                    Map.of("tier", tier.displayName()));
        } else if (result.failure() == Failure.TIER_CHANGED) {
            // Bought on another server or changed by an admin: reload the account, then offer again.
            UUID id = player.getUniqueId();
            bank.async(() -> bank.store().account(id), account -> {
                account.ifPresent(a -> bank.cacheBoughtTier(id, a.tier()));
                bank.send(player, "upgrade.changed");
            }, error -> bank.fail(player, "upgrade", error));
        } else {
            bank.send(player, "error");
        }
    }

    // ------------------------------------------------------------------ admin

    /** @param tierId a tier id, or "default"/"reset" for the first tier */
    public void adminSet(CommandSender sender, String targetName, String tierId) {
        Tiers tiers = bank.tiers();
        boolean reset = tierId.equalsIgnoreCase("default") || tierId.equalsIgnoreCase("reset");
        Optional<Tier> tier = reset ? Optional.of(tiers.first()) : tiers.byId(tierId);
        if (tier.isEmpty()) {
            bank.send(sender, "admin.unknown-tier", vars("input", tierId,
                    "tiers", tiers.all().stream().map(Tier::id).collect(Collectors.joining(", "))));
            return;
        }
        // The first tier is stored as "none", so it follows if tiers.yml is reordered.
        String stored = tier.get().rank() == 0 ? null : tier.get().id();
        String actor = sender.getName();
        bank.async(() -> bank.store().accountByName(targetName)
                        .map(a -> Map.entry(a, bank.store().setTier(a.uuid(), stored, actor))),
                result -> {
                    if (result.isEmpty()) {
                        bank.send(sender, "unknown-player", vars("player", targetName));
                        return;
                    }
                    Account account = result.get().getKey();
                    if (!result.get().getValue().ok()) {
                        bank.send(sender, "error");
                        return;
                    }
                    bank.cacheBoughtTier(account.uuid(), stored);
                    offers.remove(account.uuid());
                    bank.messages().send(sender, "admin.tier-set", vars("player", account.name()),
                            Map.of("tier", tier.get().displayName()));
                    Player online = Bukkit.getPlayer(account.uuid());
                    if (online != null) {
                        Tier now = bank.tierOf(online);
                        if (now.isAbove(tier.get())) {
                            bank.messages().send(sender, "admin.tier-permission", vars("player", account.name()),
                                    Map.of("tier", now.displayName()));
                        }
                    }
                }, error -> bank.fail(sender, "tier change", error));
    }

    // ------------------------------------------------------------------ helpers

    /** Values describing a tier, for messages and menus. */
    public Map<String, String> details(Tier tier) {
        Messages m = bank.messages();
        InterestPlan plan = tier.plan();
        Map<String, String> v = new HashMap<>();
        v.put("id", tier.id());
        v.put("cost", bank.fmt(tier.cost()));
        v.put("max-balance", tier.maxBalance() == null ? m.raw("tiers.no-limit") : bank.fmt(tier.maxBalance()));
        v.put("cap", plan.cap() == null ? m.raw("tiers.no-limit") : bank.fmt(plan.cap()));
        v.put("online-rate", plan.onlineRate().toPlainString());
        v.put("offline-rate", plan.offlineRate().toPlainString());
        v.put("online-period", TimeText.format(Duration.ofMillis(plan.onlinePeriodMillis())));
        v.put("offline-period", TimeText.format(Duration.ofMillis(plan.offlinePeriodMillis())));
        return v;
    }
}
