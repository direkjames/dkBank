package dev.direk.dkbank.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads a YAML file from the plugin folder. Settings added in a newer dkBank version are copied in
 * automatically, with their comments, so server owners never have to regenerate their files.
 * Existing values are never changed.
 */
public final class ConfigFile {

    private ConfigFile() {
    }

    public static YamlConfiguration load(JavaPlugin plugin, String name) {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) plugin.saveResource(name, false);

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        YamlConfiguration defaults = defaults(plugin, name);
        if (defaults == null) return config;

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

        if (!added.isEmpty()) {
            try {
                config.save(file);
                plugin.getLogger().info("Added new settings to " + name + ": " + String.join(", ", added));
            } catch (IOException e) {
                plugin.getLogger().warning("Couldn't save new settings to " + name + ": " + e.getMessage());
            }
        }
        config.setDefaults(defaults); // anything still missing falls back to the built-in value
        return config;
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
