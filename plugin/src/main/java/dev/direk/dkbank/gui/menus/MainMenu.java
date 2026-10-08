package dev.direk.dkbank.gui.menus;

import dev.direk.dkbank.gui.Menu;
import dev.direk.dkbank.gui.MenuLayout;
import dev.direk.dkbank.gui.MenuManager;
import dev.direk.dkbank.gui.MenuText;
import dev.direk.dkbank.gui.MenuText.Values;
import dev.direk.dkbank.interest.InterestService.Status;
import dev.direk.dkbank.tier.Tier;
import dev.direk.dkbank.util.TimeText;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * The bank's front page: balance, wallet, tier, interest and the way to everything else. Updates itself
 * while open, so a payout counts up in front of the player.
 * <p>
 * Actions: {@code upgrade} (confirm the next tier).
 */
public final class MainMenu extends Menu {

    /** How often the open menu redraws (the payout countdown) and reloads (balance and interest). */
    private static final long REDRAW_TICKS = 100L;
    private static final int RELOAD_EVERY = 6; // redraws, so every 30 seconds

    private @Nullable Status status;
    private long statusLoadedAt;

    public MainMenu(MenuManager menus, Player player, MenuLayout layout) {
        super(menus, player, layout);
    }

    @Override
    protected void onOpen() {
        reload(true);
        int[] count = {0};
        Bukkit.getScheduler().runTaskTimer(menus.plugin(), task -> {
            if (!isOpen()) {
                task.cancel();
                return;
            }
            if (++count[0] % RELOAD_EVERY == 0) reload(true);
            else render();
        }, REDRAW_TICKS, REDRAW_TICKS);
    }

    private void reload(boolean animate) {
        refreshBalance(animate);
        menus.interest().status(player, loaded -> {
            status = loaded;
            statusLoadedAt = System.currentTimeMillis();
            if (isOpen()) render();
        });
    }

    @Override
    protected Values values() {
        Map<String, String> v = common();
        Map<String, Component> rich = richCommon();
        String loading = layout.text("loading", "…");
        if (status != null && !status.plan().onlineEnabled() && !status.plan().offlineEnabled()) {
            v.put("earning", menus.bank().fmt(status.earning()));
            v.put("next-payout", "-");
            v.put("payout-progress", "-");
        } else if (status == null) {
            v.put("earning", loading);
            v.put("next-payout", loading);
            v.put("payout-progress", loading);
        } else {
            long since = System.currentTimeMillis() - statusLoadedAt;
            long until = Math.max(1000, status.untilPayout() - since);
            long done = Math.min(status.plan().onlinePeriodMillis(), status.done() + since);
            v.put("earning", menus.bank().fmt(status.earning()));
            v.put("next-payout", TimeText.format(Duration.ofMillis(until)));
            v.put("payout-progress", TimeText.format(Duration.ofMillis(done)));
        }
        Optional<Tier> next = menus.bank().tiers().nextBuyable(menus.bank().tierOf(player));
        if (next.isPresent()) {
            v.put("next-cost", menus.bank().fmt(next.get().cost()));
            rich.put("next-tier", MenuText.trusted(next.get().displayName()));
        } else {
            v.put("next-cost", "-");
            rich.put("next-tier", MenuText.trusted(layout.text("no-next-tier", "<gray>none, you're at the top")));
        }
        return new Values(v, rich);
    }

    @Override
    protected boolean visible(MenuLayout.Entry entry) {
        // Two versions of the upgrade button: one while there's a tier to buy, one at the top.
        boolean canUpgrade = menus.bank().tiers().nextBuyable(menus.bank().tierOf(player)).isPresent();
        if (entry.key().equals("upgrade")) return canUpgrade;
        if (entry.key().equals("alt-locked")) return menus.bank().locked(player);
        if (entry.key().equals("upgrade-max")) return !canUpgrade || !player.hasPermission("dkbank.upgrade");
        return true;
    }

    @Override
    protected boolean action(String name, String arg) {
        if (!name.equals("upgrade")) return false;
        Optional<Tier> next = menus.bank().tiers().nextBuyable(menus.bank().tierOf(player));
        if (next.isEmpty()) result(false);
        else menus.openConfirmUpgrade(player, next.get());
        return true;
    }
}
