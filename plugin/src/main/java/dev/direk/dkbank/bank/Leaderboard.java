package dev.direk.dkbank.bank;

import dev.direk.dkbank.config.Settings;
import dev.direk.dkbank.money.Money;
import dev.direk.dkbank.storage.BankStore;
import dev.direk.dkbank.storage.StoreTypes.TopEntry;
import dev.direk.dkbank.storage.StoreTypes.Totals;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * The richest accounts, worked out every few minutes in the background. Views (/bank top, menus,
 * placeholders) only read the last result, so a busy scoreboard never touches the database.
 */
public final class Leaderboard {

    /**
     * The leaderboard at one moment.
     *
     * @param ranks place of each listed account, starting at 1
     */
    public record Snapshot(List<TopEntry> entries, Map<UUID, Integer> ranks, Totals totals, long time) {
        static final Snapshot EMPTY = new Snapshot(List.of(), Map.of(), new Totals(0, Money.ZERO), 0);

        /** @return the place of an account, or 0 if it isn't on the leaderboard */
        public int rank(UUID uuid) {
            return ranks.getOrDefault(uuid, 0);
        }

        /** @return the account at a place (1 = richest), or null */
        public @Nullable TopEntry at(int place) {
            return place >= 1 && place <= entries.size() ? entries.get(place - 1) : null;
        }
    }

    private final JavaPlugin plugin;
    private final BankStore store;
    private final Supplier<Settings> settings;
    private volatile Snapshot snapshot = Snapshot.EMPTY;
    private @Nullable BukkitTask task;

    public Leaderboard(JavaPlugin plugin, BankStore store, Supplier<Settings> settings) {
        this.plugin = plugin;
        this.store = store;
        this.settings = settings;
    }

    /** Starts (or restarts, e.g. after a reload) the background updates. Main thread. */
    public void start() {
        stop();
        long ticks = Math.max(20L * 60, settings.get().top().refreshMillis() / 50);
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::refresh, 40L, ticks);
    }

    public void stop() {
        if (task != null) task.cancel();
        task = null;
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    /** Works the leaderboard out again (background thread). */
    public void refresh() {
        try {
            Settings.Top s = settings.get().top();
            List<TopEntry> entries = store.top(s.size() + s.hidden().size()).stream()
                    .filter(e -> !s.hidden().contains(e.name().toLowerCase(Locale.ROOT)))
                    .limit(s.size())
                    .toList();
            Map<UUID, Integer> ranks = new HashMap<>();
            for (int i = 0; i < entries.size(); i++) ranks.put(entries.get(i).uuid(), i + 1);
            snapshot = new Snapshot(entries, Map.copyOf(ranks), store.totals(), System.currentTimeMillis());
        } catch (RuntimeException e) {
            if (plugin.isEnabled()) plugin.getLogger().log(Level.WARNING, "Couldn't update the leaderboard", e);
        }
    }
}
