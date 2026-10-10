package dev.direk.dkbank.startup;

import dev.direk.dkbank.config.Settings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * The dkBank banner in the console:
 * <ul>
 *     <li>on enable: logo, version, author, server, Java, storage, tiers, hooks and startup time</li>
 *     <li>once the server finishes loading: the economy, AFK detection and the dkCore link, because
 *     economy plugins and dkCore can enable after dkBank</li>
 *     <li>instead, a red banner when dkBank can't start (a broken file or no database)</li>
 * </ul>
 * The logo is sent as plain text and every value goes in as an unparsed placeholder, so characters like
 * {@code \} and {@code <} can't break the formatting. With {@code startup-banner: false} it falls back to
 * plain log lines. Author: direk james
 */
public final class StartupBanner {

    private static final String[] LOGO = {
            "     _  _      ____                 _",
            "  __| || | __ | __ )   __ _  _ __  | | __",
            " / _` || |/ / |  _ \\  / _` || '_ \\ | |/ /",
            "| (_| ||   <  | |_) || (_| || | | ||   <",
            " \\__,_||_|\\_\\ |____/  \\__,_||_| |_||_|\\_\\",
    };

    private static final TextColor GOLD = TextColor.color(0xffc94d);
    private static final TextColor GREEN = TextColor.color(0x4dd88a);
    private static final String ACCENT = "#ffc94d";
    private static final String LINE = "<dark_gray>" + "-".repeat(56);
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final Plugin plugin;
    private final Logger log;
    private boolean enabled = true;

    public StartupBanner(Plugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    /** Follows {@code startup-banner} in config.yml (also after /bank admin reload). */
    public void enabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Printed once dkBank has enabled.
     *
     * @param storage  e.g. "SQLite (bank.db)"
     * @param tiers    tier ids, lowest first
     * @param papi     whether the PlaceholderAPI placeholders were registered
     */
    public void printStartup(String storage, long storageMs, List<String> tiers, boolean papi, long totalMs) {
        boolean dkCore = Bukkit.getPluginManager().getPlugin("dkCore") != null;
        if (!enabled) {
            log.info("dkBank " + version() + " by direk james enabled in " + totalMs + " ms (storage: " + storage
                    + ", tiers: " + String.join(", ", tiers) + ")");
            return;
        }
        blank();
        line(LINE);
        logo();
        blank();
        line("  <white><bold>dkBank</bold></white> <gray>v<v></gray>  <dark_gray>|</dark_gray>  <gray>by</gray> <" + ACCENT + ">direk james",
                v(version()));
        line("  <dark_gray><italic>Interest, tiers and bank menus for your economy");
        line(LINE);
        row("Server", "<v>", v(Bukkit.getName() + " " + Bukkit.getMinecraftVersion()));
        row("Java", "<v>", v(Runtime.version() + " (" + System.getProperty("java.vendor", "?") + ")"));
        row("Storage", "<green><v></green> <dark_gray>opened in <ms> ms", v(storage), t("ms", storageMs));
        row("Tiers", "<white><n></white> <dark_gray>(<v>)", t("n", tiers.size()), v(String.join(", ", tiers)));
        row("Placeholders", papi ? "<green>%dkbank_...% ready" : "<gray>PlaceholderAPI not installed");
        row("dk suite", dkCore ? "<gray>dkCore found, links when it starts" : "<gray>standalone (dkCore not installed)");
        row("Status", "<green>Enabled</green> <dark_gray>in <ms> ms", t("ms", totalMs));
        row("Economy", "<dark_gray>checked when the server finishes loading...");
        line(LINE);
        blank();
    }

    /**
     * Printed when the server finishes loading (and after a /reload), once economy plugins and dkCore
     * have had their chance to start.
     *
     * @param vaultInstalled whether Vault (or VaultUnlocked) is installed
     * @param economy        the economy plugin's name, or null if none is connected to Vault
     * @param afk            what decides who is AFK, e.g. "dkCore, idle 5m, 3 placeholders"
     */
    public void printReady(boolean vaultInstalled, @Nullable String economy, String afk) {
        Plugin core = Bukkit.getPluginManager().getPlugin("dkCore");
        if (!enabled) {
            if (!vaultInstalled) {
                log.severe("Vault isn't installed. dkBank needs Vault (or VaultUnlocked) to move money between wallets and banks.");
            } else if (economy == null) {
                log.severe("Vault is installed but no economy plugin is connected to it (e.g. EssentialsX or CMI).");
            } else {
                log.info("Economy: " + economy + " (through Vault)");
            }
            if (core != null && core.isEnabled()) log.info("Linked to dkCore " + core.getPluginMeta().getVersion());
            return;
        }
        line(LINE);
        line("  <gradient:" + ACCENT + ":#4dd88a><bold>dkBank</bold></gradient> <gray>ready");
        line(LINE);
        if (!vaultInstalled) {
            row("Economy", "<red>Vault isn't installed");
            row("Fix", "<white>Install Vault (or VaultUnlocked) and an economy plugin");
        } else if (economy == null) {
            row("Economy", "<red>no economy plugin connected to Vault");
            row("Fix", "<white>Install an economy plugin, e.g. EssentialsX or CMI");
        } else {
            row("Economy", "<green><v></green> <dark_gray>(through Vault)", v(economy));
        }
        row("AFK", "<white><v>", v(afk));
        if (core == null) {
            row("dk suite", "<gray>standalone (dkCore not installed)");
        } else if (core.isEnabled()) {
            row("dk suite", "<green>linked to dkCore</green> <gray>v<v>", v(core.getPluginMeta().getVersion()));
        } else {
            row("dk suite", "<red>dkCore is installed but disabled");
        }
        if (!vaultInstalled || economy == null) {
            line("  <red>Deposits and withdrawals are off until this is fixed.");
        }
        line(LINE);
    }

    /** Printed instead of the startup banner when dkBank can't start. Always shown, even when turned off. */
    public void printFailed(String problem, String reason, String fix) {
        String red = "<red>" + "-".repeat(56);
        blank();
        line(red);
        line("  <white><bold>dkBank</bold></white> <gray>v<v></gray>  <red><bold>FAILED TO START", v(version()));
        row("Problem", "<red><v>", v(problem));
        row("Reason", "<red><v>", v(reason));
        row("Fix", "<white><v>", v(fix));
        line("  <red>dkBank is disabled. Nothing was changed.");
        line(red);
        blank();
    }

    /** Short goodbye on shutdown. */
    public void printShutdown() {
        if (!enabled) return;
        line("<dark_gray>[</dark_gray><gradient:" + ACCENT + ":#4dd88a>dkBank</gradient><dark_gray>]</dark_gray> "
                + "<gray>v<v> disabled. All accounts saved.", v(version()));
    }

    /** e.g. "SQLite (bank.db)" or "MySQL (localhost:3306/dkbank)". Never includes the password. */
    public static String describe(Settings.Storage storage) {
        return switch (storage.type()) {
            case SQLITE -> "SQLite (" + storage.sqliteFile() + ")";
            case MYSQL -> "MySQL (" + storage.host() + ":" + storage.port() + "/" + storage.database() + ")";
        };
    }

    /** e.g. "dkCore, idle 5m, 3 placeholders", or "off". */
    public static String describeAfk(Settings.AfkDetection afk, List<String> sourcePlugins, boolean papi) {
        if (!afk.enabled()) return "off (AFK time earns the online rate)";
        StringBuilder out = new StringBuilder();
        if (afk.useAfkPlugins() && !sourcePlugins.isEmpty()) out.append(String.join(", ", sourcePlugins));
        if (afk.idleAfterMillis() > 0) {
            if (!out.isEmpty()) out.append(", ");
            out.append("idle ").append(dev.direk.dkbank.util.TimeText.format(java.time.Duration.ofMillis(afk.idleAfterMillis())));
        }
        if (papi && !afk.placeholders().isEmpty()) {
            if (!out.isEmpty()) out.append(", ");
            int n = afk.placeholders().size();
            out.append(n).append(n == 1 ? " placeholder" : " placeholders");
        }
        return out.isEmpty() ? "nothing set (see afk-detection in config.yml)" : out.toString();
    }

    // ---- helpers ----

    /** Logo lines as plain text, gold at the top fading to green at the bottom. */
    private void logo() {
        CommandSender console = Bukkit.getConsoleSender();
        for (int i = 0; i < LOGO.length; i++) {
            float progress = LOGO.length == 1 ? 0f : (float) i / (LOGO.length - 1);
            console.sendMessage(Component.text(LOGO[i], TextColor.lerp(progress, GOLD, GREEN)));
        }
    }

    private void row(String label, String valueMiniMessage, TagResolver... values) {
        line("  <" + ACCENT + ">" + String.format(Locale.ROOT, "%-13s", label) + "</" + ACCENT + "><gray>" + valueMiniMessage, values);
    }

    private void line(String miniMessage, TagResolver... values) {
        Bukkit.getConsoleSender().sendMessage(MM.deserialize(miniMessage, TagResolver.resolver(values)));
    }

    private void blank() {
        Bukkit.getConsoleSender().sendMessage(Component.text(" "));
    }

    /** A value placeholder {@code <v>} shown exactly as written. */
    private static TagResolver v(String value) {
        return Placeholder.unparsed("v", value);
    }

    private static TagResolver t(String key, Object value) {
        return Placeholder.unparsed(key, String.valueOf(value));
    }

    private String version() {
        return plugin.getPluginMeta().getVersion();
    }
}
