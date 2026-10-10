package dev.direk.dkbank.interest;

import dev.direk.dkbank.api.AfkSource;
import dev.direk.dkbank.config.Settings.AfkDetection;
import dev.direk.dkbank.hook.PapiHook;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Decides whether an online player is AFK, so AFK time earns the offline rate instead of the online one.
 * A player is AFK if any registered {@link AfkSource} says so (e.g. dkCore with dkAFK), if they've been
 * idle too long (Paper tracks this for every player), or if any of the configured placeholders from an
 * AFK plugin says so. Main thread only.
 */
public final class AfkDetector {

    private final Logger log;
    private boolean warned;
    /** Plugins whose AFK source threw an error, so each is only reported once. */
    private final Set<String> brokenSources = new HashSet<>();

    public AfkDetector(Logger log) {
        this.log = log;
    }

    public boolean isAfk(Player player, AfkDetection settings) {
        if (!settings.enabled()) return false;
        if (settings.useAfkPlugins() && fromSources(player)) return true;
        if (settings.idleAfterMillis() > 0 && player.getIdleDuration().toMillis() >= settings.idleAfterMillis()) {
            return true;
        }
        if (settings.placeholders().isEmpty() || !Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            return false;
        }
        for (String placeholder : settings.placeholders()) {
            try {
                String value = PapiHook.apply(player, placeholder).trim().toLowerCase(Locale.ROOT);
                if (settings.afkValues().contains(value)) return true;
            } catch (RuntimeException | LinkageError e) {
                if (!warned) {
                    warned = true; // once, not every minute for every player
                    log.log(Level.WARNING, "Couldn't check the AFK placeholder " + placeholder, e);
                }
            }
        }
        return false;
    }

    /** True if any plugin that registered an {@link AfkSource} reports the player as AFK. */
    private boolean fromSources(Player player) {
        for (RegisteredServiceProvider<AfkSource> reg : Bukkit.getServicesManager().getRegistrations(AfkSource.class)) {
            try {
                if (reg.getProvider().isAfk(player.getUniqueId())) return true;
            } catch (RuntimeException | LinkageError e) {
                if (brokenSources.add(reg.getPlugin().getName())) {
                    log.log(Level.WARNING, "The AFK source from " + reg.getPlugin().getName() + " failed", e);
                }
            }
        }
        return false;
    }

    /** Names of the plugins that registered an AFK source, for the startup banner. */
    public static Set<String> sourcePlugins() {
        Set<String> names = new java.util.LinkedHashSet<>();
        for (RegisteredServiceProvider<AfkSource> reg : Bukkit.getServicesManager().getRegistrations(AfkSource.class)) {
            names.add(reg.getPlugin().getName());
        }
        return names;
    }
}
