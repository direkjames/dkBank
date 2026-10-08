package dev.direk.dkbank.config;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.List;
import java.util.Map;

/**
 * messages.yml. Every player-facing text is MiniMessage, with {@code <prefix>} and named values like
 * {@code <amount>}. Values are inserted as plain text, so a player name can never inject formatting.
 */
public final class Messages {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final YamlConfiguration file;
    private final TagResolver prefix;

    public Messages(YamlConfiguration file) {
        this.file = file;
        this.prefix = Placeholder.parsed("prefix", file.getString("prefix", ""));
    }

    /** @return the raw text at {@code key}, or "" if it's missing or turned off */
    public String raw(String key) {
        if (file.isList(key)) return String.join("\n", file.getStringList(key));
        return file.getString(key, "");
    }

    public Component render(String key, Map<String, String> vars) {
        return renderText(raw(key), vars);
    }

    public Component renderText(String text, Map<String, String> vars) {
        TagResolver.Builder resolvers = TagResolver.builder().resolver(prefix);
        vars.forEach((name, value) -> resolvers.resolver(Placeholder.unparsed(name, value)));
        return MM.deserialize(text, resolvers.build());
    }

    /** Sends {@code key} if it isn't empty. Lists in messages.yml are sent as several lines. */
    public void send(Audience to, String key, Map<String, String> vars) {
        if (file.isList(key)) {
            List<String> lines = file.getStringList(key);
            for (String line : lines) to.sendMessage(renderText(line, vars));
            return;
        }
        String text = raw(key);
        if (!text.isEmpty()) to.sendMessage(renderText(text, vars));
    }

    public void send(Audience to, String key) {
        send(to, key, Map.of());
    }
}
