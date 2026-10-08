package dev.direk.dkbank.gui;

import dev.direk.dkbank.hook.PapiHook;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;

/**
 * Text in menus: MiniMessage, PlaceholderAPI placeholders (if installed), and named values.
 * <ul>
 *     <li>Plain values ({@code <balance>}, {@code <target>}) are inserted as text, so a player name can never
 *     add formatting.</li>
 *     <li>Rich values ({@code <tier>}, {@code <line>}) are finished components, e.g. a tier's display name.</li>
 * </ul>
 * Item names and lore aren't italic unless the text says so.
 */
public final class MenuText {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private MenuText() {
    }

    /** Values for one render. */
    public record Values(Map<String, String> plain, Map<String, Component> rich) {
        public static final Values NONE = new Values(Map.of(), Map.of());
    }

    public static Component render(String text, Player viewer, Values values) {
        if (text.isEmpty()) return Component.empty();
        String source = text.indexOf('%') >= 0 && Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")
                ? PapiHook.apply(viewer, text) : text;
        TagResolver.Builder resolvers = TagResolver.builder();
        values.plain().forEach((name, value) -> resolvers.resolver(Placeholder.unparsed(name, value)));
        values.rich().forEach((name, value) -> resolvers.resolver(Placeholder.component(name, value)));
        return MM.deserialize(source, resolvers.build()).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    /** MiniMessage from the server's own files (e.g. a tier's display name). */
    public static Component trusted(String miniMessage) {
        return MM.deserialize(miniMessage);
    }

    /** Fills {@code <name>} placeholders in plain text such as a material name. */
    public static String fill(String text, Map<String, String> plain) {
        if (text.indexOf('<') < 0) return text;
        String result = text;
        for (Map.Entry<String, String> e : plain.entrySet()) result = result.replace("<" + e.getKey() + ">", e.getValue());
        return result;
    }
}
