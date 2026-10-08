package dev.direk.dkbank.gui.menus;

import dev.direk.dkbank.gui.Menu;
import dev.direk.dkbank.gui.MenuLayout;
import dev.direk.dkbank.gui.MenuManager;
import dev.direk.dkbank.gui.MenuText;
import dev.direk.dkbank.gui.MenuText.Values;
import dev.direk.dkbank.tier.Tier;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.Map;

/**
 * "Upgrade to Gold for 150,000?" with confirm and cancel. Values: the new tier's ({@code <tier>},
 * {@code <icon>}, {@code <cost>}, {@code <max-balance>}, {@code <cap>}, rates) and {@code <current>}.
 * Actions: {@code confirm}, and {@code back} returns to the tiers menu.
 */
public final class ConfirmUpgradeMenu extends Menu {

    private final Tier tier;
    private boolean bought;

    public ConfirmUpgradeMenu(MenuManager menus, Player player, MenuLayout layout, Tier tier) {
        super(menus, player, layout);
        this.tier = tier;
    }

    @Override
    protected void onOpen() {
        refreshBalance(false);
    }

    @Override
    protected Values values() {
        Map<String, String> v = common();
        Map<String, Component> rich = richCommon();
        v.putAll(menus.tierService().details(tier));
        v.put("icon", tier.icon());
        BigDecimal missing = balance == null ? BigDecimal.ZERO : tier.cost().subtract(balance).max(BigDecimal.ZERO);
        v.put("missing", menus.bank().fmt(missing));
        rich.put("current", rich.get("tier"));
        rich.put("tier", MenuText.trusted(tier.displayName()));
        return new Values(v, rich);
    }

    @Override
    protected boolean visible(MenuLayout.Entry entry) {
        // "confirm" while they can pay, "not-enough" while they can't
        boolean affordable = balance == null || balance.compareTo(tier.cost()) >= 0;
        if (entry.key().equals("confirm")) return affordable;
        if (entry.key().equals("not-enough")) return !affordable;
        return true;
    }

    @Override
    protected void back() {
        menus.open("tiers", player);
    }

    @Override
    protected boolean action(String name, String arg) {
        if (!name.equals("confirm")) return false;
        if (bought) return true;
        bought = true; // one click buys once, however fast they click
        menus.tierService().buy(player, tier.id(), tier.cost(), success -> {
            result(success);
            if (player.isOnline()) menus.open("tiers", player);
        });
        return true;
    }
}
