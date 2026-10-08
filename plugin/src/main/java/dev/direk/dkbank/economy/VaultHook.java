package dev.direk.dkbank.economy;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jspecify.annotations.Nullable;

/**
 * The only class that touches Vault's types. It's only loaded once Vault is known to be installed,
 * so dkBank still starts (and explains what's missing) on a server without Vault.
 */
final class VaultHook {

    private final Economy economy;

    private VaultHook(Economy economy) {
        this.economy = economy;
    }

    /** @return a hook, or null if no economy plugin has registered with Vault yet */
    static @Nullable VaultHook find() {
        RegisteredServiceProvider<Economy> provider = Bukkit.getServicesManager().getRegistration(Economy.class);
        return provider == null ? null : new VaultHook(provider.getProvider());
    }

    String name() {
        return economy.getName();
    }

    double balance(OfflinePlayer player) {
        return economy.getBalance(player);
    }

    boolean withdraw(OfflinePlayer player, double amount) {
        EconomyResponse response = economy.withdrawPlayer(player, amount);
        return response != null && response.transactionSuccess();
    }

    boolean deposit(OfflinePlayer player, double amount) {
        EconomyResponse response = economy.depositPlayer(player, amount);
        return response != null && response.transactionSuccess();
    }

    String format(double amount) {
        return economy.format(amount);
    }
}
