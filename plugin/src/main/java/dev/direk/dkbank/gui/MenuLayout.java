package dev.direk.dkbank.gui;

import net.kyori.adventure.key.Key;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * One menu file from the menus folder:
 * <pre>
 * title: "..."
 * rows: 5
 * sounds: {open: ..., click: ..., success: ..., error: ...}
 * fill:   {item}          # every slot nothing else uses
 * border: {item}          # the outer ring, drawn over the fill
 * items:                  # fixed items
 *   name: {item, slot: 13 (or slots: [10-16, 20]), action(s): ..., permission: ...}
 * templates:              # items the menu fills in itself (history lines, player heads, tiers)
 *   name: {item}
 * slots:                  # named slot lists, e.g. where history lines go
 *   name: [10-16, 19-25]
 * texts:                  # short texts used inside other values
 *   name: "..."
 * </pre>
 */
public final class MenuLayout {

    /** A fixed item. */
    public record Entry(String key, ItemSpec item, List<Integer> slots, List<String> actions, @Nullable String permission) {
    }

    private final String name;
    private final YamlConfiguration file;
    private final String title;
    private final int rows;
    private final @Nullable ItemSpec fill;
    private final @Nullable ItemSpec border;
    private final List<Entry> items = new ArrayList<>();
    private final Map<String, ItemSpec> templates = new HashMap<>();
    private final Map<String, List<Integer>> slots = new HashMap<>();
    private final Map<String, Key> sounds = new HashMap<>();

    public MenuLayout(String name, YamlConfiguration file, Logger log) {
        this.name = name;
        this.file = file;
        this.title = file.getString("title", "dkBank");
        this.rows = Math.max(1, Math.min(6, file.getInt("rows", 3)));
        int size = rows * 9;
        String where = "menus/" + name + ".yml";

        ConfigurationSection fillSection = file.getConfigurationSection("fill");
        this.fill = fillSection == null ? null : ItemSpec.parse(fillSection);
        ConfigurationSection borderSection = file.getConfigurationSection("border");
        this.border = borderSection == null ? null : ItemSpec.parse(borderSection);

        ConfigurationSection itemSection = file.getConfigurationSection("items");
        if (itemSection != null) {
            for (String key : itemSection.getKeys(false)) {
                ConfigurationSection s = itemSection.getConfigurationSection(key);
                if (s == null) continue;
                List<Integer> at = slotsOf(s, "slot", size, where + " → items." + key, log);
                at.addAll(slotsOf(s, "slots", size, where + " → items." + key, log));
                if (at.isEmpty()) {
                    log.warning(where + " → items." + key + " has no slot. It isn't shown.");
                    continue;
                }
                List<String> actions = new ArrayList<>(s.getStringList("actions"));
                String action = s.getString("action");
                if (action != null && !action.isBlank()) actions.addFirst(action);
                items.add(new Entry(key, ItemSpec.parse(s), at, List.copyOf(actions), s.getString("permission")));
            }
        }
        ConfigurationSection templateSection = file.getConfigurationSection("templates");
        if (templateSection != null) {
            for (String key : templateSection.getKeys(false)) {
                ConfigurationSection s = templateSection.getConfigurationSection(key);
                if (s != null) templates.put(key, ItemSpec.parse(s));
            }
        }
        ConfigurationSection slotSection = file.getConfigurationSection("slots");
        if (slotSection != null) {
            for (String key : slotSection.getKeys(false)) slots.put(key, slotsOf(slotSection, key, size, where + " → slots." + key, log));
        }
        ConfigurationSection soundSection = file.getConfigurationSection("sounds");
        if (soundSection != null) {
            for (String key : soundSection.getKeys(false)) {
                String value = soundSection.getString(key, "");
                if (value == null || value.isBlank()) continue;
                String id = value.trim().toLowerCase(Locale.ROOT);
                if (Key.parseable(id)) sounds.put(key, Key.key(id));
                else log.warning(where + " → sounds." + key + ": '" + value + "' isn't a sound name.");
            }
        }
    }

    /** Reads "13", [10, 11], ["10-16", 19] or "10-16, 19" into slot numbers inside the menu. */
    private static List<Integer> slotsOf(ConfigurationSection s, String path, int size, String where, Logger log) {
        List<Integer> result = new ArrayList<>();
        if (!s.contains(path)) return result;
        List<String> parts = new ArrayList<>();
        if (s.isList(path)) {
            for (Object o : s.getList(path, List.of())) parts.add(String.valueOf(o));
        } else {
            for (String part : String.valueOf(s.get(path)).split(",")) parts.add(part);
        }
        for (String raw : parts) {
            String part = raw.trim();
            if (part.isEmpty()) continue;
            try {
                int dash = part.indexOf('-', 1);
                int from = Integer.parseInt((dash < 0 ? part : part.substring(0, dash)).trim());
                int to = dash < 0 ? from : Integer.parseInt(part.substring(dash + 1).trim());
                int low = Math.min(from, to);
                int high = Math.max(from, to);
                if (low < 0 || high >= size) {
                    log.warning(where + ": '" + part + "' goes outside the menu (slots 0-" + (size - 1) + "). Only the slots inside are used.");
                }
                for (int i = Math.max(0, low); i <= Math.min(size - 1, high); i++) result.add(i);
            } catch (NumberFormatException e) {
                log.warning(where + ": '" + part + "' isn't a slot number or range like 10-16.");
            }
        }
        return result;
    }

    public String name() {
        return name;
    }

    public String title() {
        return title;
    }

    public int rows() {
        return rows;
    }

    public int size() {
        return rows * 9;
    }

    public @Nullable ItemSpec fill() {
        return fill;
    }

    public @Nullable ItemSpec border() {
        return border;
    }

    public List<Entry> items() {
        return items;
    }

    public @Nullable ItemSpec template(String key) {
        return templates.get(key);
    }

    public List<Integer> slots(String key) {
        return slots.getOrDefault(key, List.of());
    }

    public String text(String key, String fallback) {
        return file.getString("texts." + key, fallback);
    }

    public @Nullable Key sound(String key) {
        return sounds.get(key);
    }

    /** The whole file, for settings only one menu has (e.g. preset amounts). */
    public YamlConfiguration file() {
        return file;
    }

    /** Whether {@code slot} is on the outer ring. */
    public boolean isBorder(int slot) {
        int row = slot / 9;
        int column = slot % 9;
        return row == 0 || row == rows - 1 || column == 0 || column == 8;
    }
}
