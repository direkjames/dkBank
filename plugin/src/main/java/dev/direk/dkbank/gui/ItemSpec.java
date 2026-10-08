package dev.direk.dkbank.gui;

import dev.direk.dkbank.gui.MenuText.Values;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * How an item in a menu looks, from a menu file:
 * <pre>
 * material: GOLD_BLOCK        # or a value like "&lt;tier-icon&gt;"; AIR leaves the slot empty
 * amount: 1
 * name: "&lt;gold&gt;Bank balance"
 * lore: ["&lt;white&gt;&lt;balance&gt;"]
 * glow: true                  # enchantment shine
 * head: viewer                # PLAYER_HEAD only: viewer (the player looking) or target (e.g. who you're paying)
 * texture: "eyJ0..."          # a custom head (e.g. the Value from minecraft-heads.com); material can be left out
 *                             # also as material: "head:eyJ0..." (handy for tier icons and history items)
 * hide-tooltip: true          # no tooltip at all (for background panes)
 * </pre>
 */
public record ItemSpec(String material, int amount, @Nullable String name, List<String> lore, boolean glow,
                       @Nullable String head, @Nullable String texture, boolean hideTooltip) {

    private static final Set<String> WARNED = Collections.synchronizedSet(new HashSet<>());

    public static ItemSpec parse(ConfigurationSection s) {
        int amount = Math.max(1, Math.min(64, s.getInt("amount", 1)));
        List<String> lore = s.isList("lore") ? s.getStringList("lore")
                : s.isString("lore") ? List.of(s.getString("lore", "")) : List.of();
        String texture = s.getString("texture");
        String material = s.getString("material", texture != null ? "PLAYER_HEAD" : "STONE");
        return new ItemSpec(material, amount, s.getString("name"), lore,
                s.getBoolean("glow", false), s.getString("head"), texture, s.getBoolean("hide-tooltip", false));
    }

    /** Where it's drawn: the viewer, the values to fill in, and whose head {@code head: target} shows. */
    public record Context(Player viewer, Values values, @Nullable Player target) {
    }

    /** @return the item, or null for AIR */
    public @Nullable ItemStack build(Context ctx, Logger log) {
        String filled = MenuText.fill(material, ctx.values().plain()).trim();
        String headTexture = texture;
        if (filled.regionMatches(true, 0, "head:", 0, 5)) { // material: "head:<texture>"
            headTexture = filled.substring(5).trim();
            filled = "PLAYER_HEAD";
        }
        String materialName = filled.toUpperCase(Locale.ROOT);
        if (materialName.equals("AIR")) return null;
        Material type = Material.matchMaterial(materialName);
        if (type == null || !type.isItem() || type.isAir()) {
            if (WARNED.add(materialName)) log.warning("Unknown item '" + materialName + "' in a menu file. Showing a barrier instead.");
            type = Material.BARRIER;
        }
        ItemStack item = new ItemStack(type, amount);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        if (name != null) meta.displayName(MenuText.render(name, ctx.viewer(), ctx.values()));
        if (!lore.isEmpty()) {
            List<Component> lines = new ArrayList<>(lore.size());
            for (String line : lore) lines.add(MenuText.render(line, ctx.viewer(), ctx.values()));
            meta.lore(lines);
        }
        if (glow) meta.setEnchantmentGlintOverride(true);
        if (hideTooltip) meta.setHideTooltip(true);
        meta.addItemFlags(ItemFlag.values());
        if (meta instanceof SkullMeta skull) {
            if (headTexture != null && !headTexture.isBlank()) {
                String value = MenuText.fill(headTexture, ctx.values().plain());
                var profile = Heads.profile(value);
                if (profile != null) skull.setPlayerProfile(profile);
                else if (WARNED.add("texture:" + value)) {
                    log.warning("'" + (value.length() > 40 ? value.substring(0, 40) + "…" : value)
                            + "' in a menu file isn't a head texture. Use the Value from minecraft-heads.com.");
                }
            } else if (head != null) {
                Player owner = head.equalsIgnoreCase("target") ? ctx.target() : ctx.viewer();
                if (owner != null) skull.setPlayerProfile(owner.getPlayerProfile());
            }
        }
        item.setItemMeta(meta);
        return item;
    }
}
