package dev.direk.dkbank.hook;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.entity.Player;

/**
 * The only class that touches PlaceholderAPI. Only call it after checking that PlaceholderAPI is enabled.
 */
public final class PapiHook {

    private PapiHook() {
    }

    public static String apply(Player player, String text) {
        return PlaceholderAPI.setPlaceholders(player, text);
    }

    /** Registers the %dkbank_...% placeholders. */
    public static boolean register(dev.direk.dkbank.DkBankPlugin plugin) {
        return new DkBankExpansion(plugin).register();
    }
}
