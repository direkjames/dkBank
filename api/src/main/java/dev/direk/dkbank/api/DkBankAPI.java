package dev.direk.dkbank.api;

import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Entry point of the dkBank Developer API.
 * <p>
 * Get it once dkBank is enabled (add {@code depend: [dkBank]} or {@code softdepend: [dkBank]} to your
 * plugin.yml):
 * <pre>{@code
 * DkBankAPI bank = DkBankAPI.get();
 * }</pre>
 * Nothing in this API returns or accepts {@code null} unless marked {@code @Nullable}.
 * <p>
 * Banking methods (balances, deposits, withdrawals, transfers, tiers) and events are added in later
 * versions. Methods are only ever added to this interface, never removed without a deprecation period.
 */
public interface DkBankAPI {

    /**
     * @return the running dkBank version, e.g. {@code 1.0.0}
     */
    String version();

    /**
     * @return the dkBank API
     * @throws IllegalStateException if dkBank isn't installed or enabled yet
     */
    static DkBankAPI get() {
        RegisteredServiceProvider<DkBankAPI> provider = Bukkit.getServicesManager().getRegistration(DkBankAPI.class);
        if (provider == null) {
            throw new IllegalStateException("dkBank isn't enabled. Add dkBank to depend or softdepend in your plugin.yml.");
        }
        return provider.getProvider();
    }

    /**
     * @return true if dkBank is installed and enabled
     */
    static boolean isAvailable() {
        return Bukkit.getServicesManager().isProvidedFor(DkBankAPI.class);
    }
}
