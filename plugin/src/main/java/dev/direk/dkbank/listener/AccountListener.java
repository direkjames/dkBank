package dev.direk.dkbank.listener;

import dev.direk.dkbank.DkBankPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;

/**
 * Loads accounts at login and keeps track of the economy plugin.
 */
public final class AccountListener implements Listener {

    private final DkBankPlugin plugin;

    public AccountListener(DkBankPlugin plugin) {
        this.plugin = plugin;
    }

    /** Runs on the login thread, so the database work doesn't touch the main thread. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        plugin.bank().loadAccount(event.getUniqueId(), event.getName(), event.getAddress());
    }

    /** Tells players locked by the alt-account limit why, and staff about updates, a moment after they join. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String newer = plugin.updates().newerVersion();
        if (newer != null && player.hasPermission("dkbank.admin") && plugin.bank().settings().updateChecker()) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) plugin.bank().messages().send(player, "admin.update-available",
                        java.util.Map.of("version", newer, "current", plugin.version()));
            }, 80L);
        }
        if (!plugin.bank().settings().alts().tellPlayer()) return;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && plugin.bank().locked(player)) {
                plugin.bank().messages().send(player, "alts.locked-notice",
                        java.util.Map.of("max", String.valueOf(plugin.bank().settings().alts().maxPerAddress())));
            }
        }, 60L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        java.util.UUID uuid = event.getPlayer().getUniqueId();
        // A tick later, and only if they didn't just log in again (a duplicate login quits the old session
        // after the new one loaded its account).
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (plugin.getServer().getPlayer(uuid) != null) return;
            plugin.bank().unload(uuid);
            plugin.tiers().forget(uuid);
        });
    }

    /** Economy plugins can register after dkBank enables; look again when they do. */
    @EventHandler
    public void onServiceRegister(ServiceRegisterEvent event) {
        if (isEconomy(event.getProvider().getService())) plugin.bank().wallet().refresh();
    }

    @EventHandler
    public void onServiceUnregister(ServiceUnregisterEvent event) {
        if (isEconomy(event.getProvider().getService())) plugin.bank().wallet().refresh();
    }

    /** Once every plugin has loaded, tell the owner if there's no economy to work with. */
    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        plugin.reportEconomy();
    }

    private static boolean isEconomy(Class<?> service) {
        return service.getName().equals("net.milkbowl.vault.economy.Economy");
    }
}
