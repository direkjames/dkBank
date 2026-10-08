package dev.direk.dkbank.economy;

import dev.direk.dkbank.money.Money;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;

/**
 * The player's wallet: their balance in the server's economy plugin (EssentialsX, CMI, ...) via Vault.
 * <p>
 * Economy plugins expect to be called from the main thread, so every method here must be.
 * The economy is looked up when first needed, because economy plugins may enable after dkBank.
 */
public final class Wallet {

    private @Nullable VaultHook hook;

    private @Nullable VaultHook hook() {
        if (hook == null && Bukkit.getPluginManager().getPlugin("Vault") != null) {
            hook = VaultHook.find();
        }
        return hook;
    }

    /** Forget the economy found so far, e.g. after another economy plugin registers. */
    public void refresh() {
        hook = null;
    }

    public boolean available() {
        return hook() != null;
    }

    public boolean vaultInstalled() {
        return Bukkit.getPluginManager().getPlugin("Vault") != null;
    }

    /** @return the economy plugin's name, or "none" */
    public String providerName() {
        VaultHook h = hook();
        return h == null ? "none" : h.name();
    }

    public BigDecimal balance(OfflinePlayer player) {
        VaultHook h = hook();
        return h == null ? Money.ZERO : Money.fromDouble(h.balance(player));
    }

    /** @return true if the money was taken from the wallet */
    public boolean take(OfflinePlayer player, BigDecimal amount) {
        VaultHook h = hook();
        return h != null && h.withdraw(player, amount.doubleValue());
    }

    /** @return true if the money was added to the wallet */
    public boolean give(OfflinePlayer player, BigDecimal amount) {
        VaultHook h = hook();
        return h != null && h.deposit(player, amount.doubleValue());
    }

    /** @return the economy plugin's own formatting of the amount, or null if unavailable */
    public @Nullable String format(BigDecimal amount) {
        VaultHook h = hook();
        return h == null ? null : h.format(amount.doubleValue());
    }
}
