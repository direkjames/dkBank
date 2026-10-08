package dev.direk.dkbank.listener;

import dev.direk.dkbank.DkBankPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
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
        plugin.bank().loadAccount(event.getUniqueId(), event.getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        plugin.bank().unload(event.getPlayer().getUniqueId());
        plugin.tiers().forget(event.getPlayer().getUniqueId());
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
