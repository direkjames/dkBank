package dev.direk.dkbank.hook;

import dev.direk.dkbank.DkBankPlugin;
import org.bstats.charts.SimplePie;

import java.util.Locale;

/**
 * Anonymous usage numbers on bstats.org (how many servers, which versions and settings). Server owners
 * can turn it off in config.yml ({@code metrics: false}) or for every plugin in plugins/bStats/config.yml.
 */
public final class Metrics {

    /** dkBank's id on bstats.org. 0 until it's registered there: nothing is sent. */
    public static final int BSTATS_ID = 0;

    private Metrics() {
    }

    public static void start(DkBankPlugin plugin) {
        if (BSTATS_ID <= 0 || !plugin.bank().settings().metrics()) return;
        org.bstats.bukkit.Metrics metrics = new org.bstats.bukkit.Metrics(plugin, BSTATS_ID);
        metrics.addCustomChart(new SimplePie("storage", () ->
                plugin.bank().settings().storage().type().name().toLowerCase(Locale.ROOT)));
        metrics.addCustomChart(new SimplePie("tiers", () -> String.valueOf(plugin.bank().tiers().all().size())));
        metrics.addCustomChart(new SimplePie("alt_limit", () -> String.valueOf(plugin.bank().settings().alts().enabled())));
        metrics.addCustomChart(new SimplePie("menus", () -> String.valueOf(plugin.bank().settings().menus().openFromCommands())));
        metrics.addCustomChart(new SimplePie("economy", () -> plugin.bank().wallet().providerName()));
    }
}
