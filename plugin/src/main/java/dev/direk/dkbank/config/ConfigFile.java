package dev.direk.dkbank.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads a YAML file from the plugin folder. Settings added in a newer dkBank version are copied in
 * automatically, with their comments, so server owners never have to regenerate their files.
 * Existing values are never changed, except settings that moved elsewhere, which are removed with a
 * note in the console.
 */
public final class ConfigFile {

    private ConfigFile() {
    }

    public static YamlConfiguration load(JavaPlugin plugin, String name) {
        return load(plugin, name, Map.of());
    }

    /**
     * @param moved settings that no longer belong in this file, and where they went
     */
    public static YamlConfiguration load(JavaPlugin plugin, String name, Map<String, String> moved) {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) plugin.saveResource(name, false);

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        YamlConfiguration defaults = defaults(plugin, name);
        if (defaults == null) return config;

        boolean changed = false;
        for (Map.Entry<String, String> entry : moved.entrySet()) {
            if (!config.contains(entry.getKey(), true)) continue;
            plugin.getLogger().info("Removed " + entry.getKey() + " (was " + config.get(entry.getKey()) + ") from "
                    + name + ": it's now " + entry.getValue() + ".");
            config.set(entry.getKey(), null);
            changed = true;
        }

        List<String> added = new ArrayList<>();
        for (String key : defaults.getKeys(true)) {
            if (config.contains(key, true)) continue;
            if (defaults.isConfigurationSection(key)) {
                config.createSection(key);
            } else {
                config.set(key, defaults.get(key));
                added.add(key);
            }
            config.setComments(key, defaults.getComments(key));
            config.setInlineComments(key, defaults.getInlineComments(key));
        }

        if (!added.isEmpty() || changed) {
            try {
                config.save(file);
                if (!added.isEmpty()) plugin.getLogger().info("Added new settings to " + name + ": " + String.join(", ", added));
            } catch (IOException e) {
                plugin.getLogger().warning("Couldn't save new settings to " + name + ": " + e.getMessage());
            }
        }
        config.setDefaults(defaults); // anything still missing falls back to the built-in value
        return config;
    }

    /**
     * Loads a file the owner fully controls (like tiers.yml): it's created from the built-in copy if
     * missing, but nothing is ever added to it, so removed entries stay removed.
     *
     * @return the file, or null if it has a mistake (logged)
     */
    public static @Nullable YamlConfiguration loadAsIs(JavaPlugin plugin, String name) {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) plugin.saveResource(name, false);
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(file);
            return config;
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().severe(name + " couldn't be read: " + e.getMessage());
            return null;
        }
    }

    /** A section and everything in it as plain maps and values. */
    public static Map<String, Object> toMap(ConfigurationSection section) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            map.put(key, value instanceof ConfigurationSection child ? toMap(child) : value);
        }
        return map;
    }

    private static @Nullable YamlConfiguration defaults(JavaPlugin plugin, String name) {
        try (InputStream in = plugin.getResource(name)) {
            if (in == null) return null;
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }
}
