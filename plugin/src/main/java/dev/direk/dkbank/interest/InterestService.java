package dev.direk.dkbank.interest;

import dev.direk.dkbank.bank.BankService;
import dev.direk.dkbank.config.Messages;
import dev.direk.dkbank.config.Settings;
import dev.direk.dkbank.storage.BankStore;
import dev.direk.dkbank.storage.StoreTypes.Beat;
import dev.direk.dkbank.storage.StoreTypes.InterestState;
import dev.direk.dkbank.storage.StoreTypes.Payout;
import dev.direk.dkbank.util.TimeText;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Pays interest.
 * <ul>
 *     <li>Join: offline interest for the time away (and anything left over from a crash).</li>
 *     <li>Every minute: each online player's time is added to their payout cycle, as active or AFK time.
 *     A full cycle pays out. Progress is saved every minute, so a crash loses at most a minute.</li>
 *     <li>Quit (and server stop): the unfinished cycle is paid, prorated.</li>
 * </ul>
 * All database work runs on one dedicated thread, so a player's join, minutes and quit are always handled
 * in the order they happened.
 */
public final class InterestService implements Listener {

    /** How often online time is recorded. */
    private static final long HEARTBEAT_TICKS = 20L * 60;
    /** Most time one heartbeat may add: a frozen or sleeping server doesn't count as playtime. */
    private static final long MAX_BEAT_MILLIS = 2 * 60_000L;

    private final JavaPlugin plugin;
    private final BankStore store;
    private final BankService bank;
    private final AfkDetector afk;
    private final ExecutorService worker;
    /** When each online player's time was last recorded. Main thread only. */
    private final Map<UUID, Long> lastBeat = new HashMap<>();
    private @Nullable BukkitTask heartbeat;

    public InterestService(JavaPlugin plugin, BankStore store, BankService bank) {
        this.plugin = plugin;
        this.store = store;
        this.bank = bank;
        this.afk = new AfkDetector(plugin.getLogger());
        this.worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "dkBank-interest");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        heartbeat = Bukkit.getScheduler().runTaskTimer(plugin, this::heartbeat, HEARTBEAT_TICKS, HEARTBEAT_TICKS);
        for (Player player : Bukkit.getOnlinePlayers()) join(player); // after a reload
    }

    /** Pays every online player's unfinished cycle, then waits for the database work to finish. */
    public void shutdown() {
        if (heartbeat != null) heartbeat.cancel();
        for (Player player : Bukkit.getOnlinePlayers()) quit(player);
        lastBeat.clear();
        worker.shutdown();
        try {
            if (!worker.awaitTermination(20, TimeUnit.SECONDS)) {
                plugin.getLogger().severe("Interest payouts didn't finish within 20 seconds of shutdown.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The interest plan for a player. Bank tiers will choose it per player. */
    public InterestPlan planFor(Player player) {
        return bank.settings().interest().plan();
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        join(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        quit(event.getPlayer());
    }

    private void join(Player player) {
        UUID uuid = player.getUniqueId();
        if (lastBeat.containsKey(uuid)) return;
        lastBeat.put(uuid, System.currentTimeMillis());
        InterestPlan plan = planFor(player);
        BigDecimal max = bank.settings().maxBalanceOrNull();
        String name = player.getName();
        submit("pay offline interest to " + name, () -> {
            if (bank.cachedBalance(uuid) == null) bank.loadAccount(uuid, name); // e.g. after a reload
            Payout payout = store.settleLogin(uuid, plan, max);
            if (payout.paid()) bank.cacheBalance(uuid, payout.balance());
            if (payout.paid() && bank.settings().interest().notifyOffline()) {
                bank.runOnMain(() -> Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    Player online = Bukkit.getPlayer(uuid);
                    if (online != null) notify(online, "interest.offline-payout", payout);
                }, bank.settings().interest().loginDelayTicks()));
            }
        });
    }

    private void quit(Player player) {
        Long last = lastBeat.remove(player.getUniqueId());
        if (last == null) return;
        Beat beat = beat(player, last, System.currentTimeMillis());
        submit("pay interest to " + player.getName() + " at logout", () -> {
            Payout payout = store.settleLogout(beat);
            if (payout.paid()) bank.cacheBalance(beat.uuid(), payout.balance());
        });
    }

    private void heartbeat() {
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            Long last = lastBeat.put(uuid, now);
            if (last == null) continue; // joined between heartbeats and not seen yet; counted from now
            Beat beat = beat(player, last, now);
            submit("record " + player.getName() + "'s online time", () -> {
                Payout payout = store.beat(beat);
                if (!payout.paid()) return;
                bank.cacheBalance(uuid, payout.balance());
                if (bank.settings().interest().notifyOnline()) {
                    bank.runOnMain(() -> {
                        Player online = Bukkit.getPlayer(uuid);
                        if (online != null) notify(online, "interest.online-payout", payout);
                    });
                }
            });
        }
    }

    private Beat beat(Player player, long last, long now) {
        long elapsed = Math.min(Math.max(0, now - last), MAX_BEAT_MILLIS);
        boolean active = !afk.isAfk(player, bank.settings().afk());
        return new Beat(player.getUniqueId(), elapsed, active, planFor(player), bank.settings().maxBalanceOrNull());
    }

    private void notify(Player player, String key, Payout payout) {
        bank.messages().send(player, key, Map.of("amount", bank.fmt(payout.amount()), "balance", bank.fmt(payout.balance()),
                "time", TimeText.format(Duration.ofMillis(payout.offlineMillis()))));
        Key sound = bank.settings().interest().sound();
        if (sound != null) player.playSound(Sound.sound(sound, Sound.Source.MASTER, 1f, 1f));
    }

    // ------------------------------------------------------------------ /bank interest

    public void showInfo(Player player) {
        UUID uuid = player.getUniqueId();
        InterestPlan plan = planFor(player);
        Long last = lastBeat.get(uuid);
        long unrecorded = last == null ? 0 : Math.min(Math.max(0, System.currentTimeMillis() - last), MAX_BEAT_MILLIS);
        submit("show interest info", () -> {
            InterestState state = store.interestState(uuid).orElse(null);
            bank.runOnMain(() -> {
                if (state == null) {
                    bank.messages().send(player, "error");
                    return;
                }
                sendInfo(player, plan, state, unrecorded);
            });
        });
    }

    private void sendInfo(Player player, InterestPlan plan, InterestState state, long unrecorded) {
        Messages m = bank.messages();
        m.send(player, "interest.info.header");
        if (plan.onlineEnabled()) {
            m.send(player, "interest.info.online", Map.of("rate", plan.onlineRate().toPlainString(),
                    "period", TimeText.format(Duration.ofMillis(plan.onlinePeriodMillis()))));
        } else {
            m.send(player, "interest.info.online-off");
        }
        if (plan.offlineEnabled()) {
            m.send(player, "interest.info.offline", Map.of("rate", plan.offlineRate().toPlainString(),
                    "period", TimeText.format(Duration.ofMillis(plan.offlinePeriodMillis())),
                    "max", TimeText.format(Duration.ofMillis(plan.offlineMaxMillis()))));
        } else {
            m.send(player, "interest.info.offline-off");
        }
        BigDecimal cap = plan.cap();
        m.send(player, "interest.info.earning", Map.of(
                "earning", bank.fmt(InterestMath.earningBase(state.base(), cap)),
                "cap", cap == null ? m.raw("interest.info.no-cap") : bank.fmt(cap)));
        if (plan.onlineEnabled() || plan.offlineEnabled()) {
            long done = Math.min(plan.onlinePeriodMillis(), state.activeMillis() + state.afkMillis() + unrecorded);
            long left = plan.onlinePeriodMillis() - done;
            m.send(player, "interest.info.next", Map.of(
                    "time", TimeText.format(Duration.ofMillis(Math.max(left, 1000))),
                    "done", TimeText.format(Duration.ofMillis(done)),
                    "period", TimeText.format(Duration.ofMillis(plan.onlinePeriodMillis()))));
        }
    }

    // ------------------------------------------------------------------ helpers

    private void submit(String what, Runnable work) {
        try {
            worker.execute(() -> {
                try {
                    work.run();
                } catch (RuntimeException e) {
                    plugin.getLogger().log(Level.SEVERE, "Couldn't " + what, e);
                }
            });
        } catch (RejectedExecutionException e) {
            plugin.getLogger().warning("Couldn't " + what + ": the plugin is shutting down.");
        }
    }
}
