package dev.direk.dkbank.api;

import java.util.UUID;

/**
 * Tells dkBank whether a player is AFK, so AFK time earns the offline interest rate instead of the
 * online one. Use it when your plugin knows better than idle time and placeholders, e.g. an AFK plugin
 * or a core plugin that collects AFK status from several plugins.
 * <p>
 * Register it with Bukkit's ServicesManager (no dependency on dkBank's internals needed):
 * <pre>{@code
 * Bukkit.getServicesManager().register(AfkSource.class, uuid -> myAfkManager.isAfk(uuid),
 *         myPlugin, ServicePriority.Normal);
 * }</pre>
 * dkBank asks every registered source, and a player is AFK if any of them says so (or if dkBank's own
 * idle time or placeholder checks do). Sources are unregistered automatically when their plugin disables.
 * <p>
 * Called on the main thread about once a minute per online player, so keep it fast: read from memory,
 * never from a database. Server owners can turn sources off with
 * {@code afk-detection.use-afk-plugins: false} in dkBank's config.yml.
 *
 * @since 1.1.0
 */
@FunctionalInterface
public interface AfkSource {

    /**
     * @param player an online player
     * @return true if the player is AFK right now
     */
    boolean isAfk(UUID player);
}
