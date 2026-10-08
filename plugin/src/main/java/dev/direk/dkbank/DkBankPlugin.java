package dev.direk.dkbank;

import dev.direk.dkbank.api.DkBankAPI;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * dkBank main class.
 */
public final class DkBankPlugin extends JavaPlugin implements DkBankAPI {

    @Override
    public void onEnable() {
        String server = getServer().getName() + " " + getServer().getMinecraftVersion();
        getLogger().info("dkBank " + version() + " enabling on " + server + " (Java " + Runtime.version().feature() + ")");

        if (!hasVault()) {
            getLogger().warning("Vault (or VaultUnlocked) wasn't found. Banking needs it to move money; install it before going live.");
        }

        getServer().getServicesManager().register(DkBankAPI.class, this, this, ServicePriority.Normal);
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
    }

    @Override
    public String version() {
        return getPluginMeta().getVersion();
    }

    private boolean hasVault() {
        // VaultUnlocked also loads under the name "Vault", so one check covers both.
        return getServer().getPluginManager().getPlugin("Vault") != null;
    }
}
