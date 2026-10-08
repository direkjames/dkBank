package dev.direk.dkbank.gui.menus;

import dev.direk.dkbank.gui.Menu;
import dev.direk.dkbank.gui.MenuLayout;
import dev.direk.dkbank.gui.MenuManager;
import dev.direk.dkbank.gui.MenuText;
import dev.direk.dkbank.gui.MenuText.Values;
import dev.direk.dkbank.tier.Tier;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Every bank tier, lowest first, in {@code slots.tiers}. Each tier uses the template for where the player
 * stands: {@code current}, {@code owned} (below theirs), {@code next} (click to upgrade), {@code locked}
 * (later) or {@code rank-only} (only through a permission). In those, {@code <tier>} and {@code <icon>} are
 * the tier's, and {@code <current>} is the player's own tier.
 */
public final class TiersMenu extends Menu {

    public TiersMenu(MenuManager menus, Player player, MenuLayout layout) {
        super(menus, player, layout);
    }

    @Override
    protected void draw() {
        List<Integer> slots = layout.slots("tiers");
        List<Tier> tiers = menus.bank().tiers().all();
        if (tiers.size() > slots.size()) {
            menus.log().warning("menus/tiers.yml has " + slots.size() + " tier slots for " + tiers.size()
                    + " tiers. Add slots under slots.tiers.");
        }
        Tier current = menus.bank().tierOf(player);
        Tier next = menus.bank().tiers().nextBuyable(current).orElse(null);
        Values base = values();
        for (int i = 0; i < Math.min(slots.size(), tiers.size()); i++) {
            Tier tier = tiers.get(i);
            String state;
            if (tier.equals(current)) state = "current";
            else if (!tier.isAbove(current)) state = "owned";
            else if (tier.equals(next)) state = "next";
            else if (tier.buyable()) state = "locked";
            else state = "rank-only";

            Map<String, String> v = new HashMap<>(base.plain());
            v.putAll(menus.tierService().details(tier));
            v.put("icon", tier.icon());
            Map<String, Component> rich = new HashMap<>(base.rich());
            rich.put("current", rich.get("tier"));
            rich.put("tier", MenuText.trusted(tier.displayName()));
            ItemStack item = template(state, new Values(v, rich), null);
            boolean buyable = state.equals("next");
            set(slots.get(i), item, type -> {
                if (buyable) menus.openConfirmUpgrade(player, tier);
                else result(false);
            });
        }
    }
}
