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
}
