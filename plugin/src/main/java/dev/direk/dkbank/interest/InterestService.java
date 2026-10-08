package dev.direk.dkbank.interest;

import dev.direk.dkbank.bank.AltGuard;
import dev.direk.dkbank.bank.BankService;
import dev.direk.dkbank.config.Messages;
import dev.direk.dkbank.config.Settings;
import dev.direk.dkbank.storage.BankStore;
import dev.direk.dkbank.storage.StoreTypes.Beat;
import dev.direk.dkbank.storage.StoreTypes.InterestState;
import dev.direk.dkbank.storage.StoreTypes.Payout;
import dev.direk.dkbank.tier.Tier;
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
    /** Online time in each player's payout cycle as last saved, and when: for placeholders. Any thread. */
    private final Map<UUID, long[]> progress = new java.util.concurrent.ConcurrentHashMap<>();
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

    /** The interest plan for a player: their bank tier's. */
    public InterestPlan planFor(Player player) {
        return bank.tierOf(player).plan();
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        join(event.getPlayer());
    }

    /** Before MONITOR: the account (and its tier) is unloaded at MONITOR. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        quit(event.getPlayer());
    }

    private void join(Player player) {
        UUID uuid = player.getUniqueId();
        if (lastBeat.containsKey(uuid)) return;
        lastBeat.put(uuid, System.currentTimeMillis());
        String name = player.getName();
        // The bought tier is only known once the account is loaded; permission tiers are checked here.
        Tier byPermission = bank.tiers().resolve(null, player::hasPermission);
        boolean bypass = player.hasPermission(AltGuard.BYPASS);
        java.net.InetAddress address = player.getAddress() == null ? null : player.getAddress().getAddress();
        submit("pay offline interest to " + name, () -> {
            if (bank.cachedBalance(uuid) == null) bank.loadAccount(uuid, name, address); // e.g. after a reload
            Tier bought = bank.tiers().bought(bank.boughtTier(uuid));
            Tier tier = bought.isAbove(byPermission) ? bought : byPermission;
            InterestPlan plan = bank.alts().isLocked(uuid, bypass) ? noInterest(tier.plan()) : tier.plan();
            Payout payout = store.settleLogin(uuid, plan, tier.maxBalance());
            progress.put(uuid, new long[]{0, System.currentTimeMillis()});
            if (payout.paid()) paid(uuid, payout, "offline");
            if (payout.paid() && bank.settings().interest().notifyOffline()) {
                bank.runOnMain(() -> Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    Player online = Bukkit.getPlayer(uuid);
                    if (online != null) notify(online, "interest.offline-payout", payout);
                }, bank.settings().interest().loginDelayTicks()));
            }
        });
    }

    private void quit(Player player) {
        progress.remove(player.getUniqueId());
        Long last = lastBeat.remove(player.getUniqueId());
        if (last == null) return;
        Beat beat = beat(player, last, System.currentTimeMillis());
        submit("pay interest to " + player.getName() + " at logout", () -> {
            Payout payout = store.settleLogout(beat);
            if (payout.paid()) paid(beat.uuid(), payout, "online");
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
                if (lastBeatContains(uuid)) progress.put(uuid, new long[]{payout.cycleMillis(), System.currentTimeMillis()});
                if (!payout.paid()) return;
                paid(uuid, payout, "online");
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
        Tier tier = bank.tierOf(player);
        InterestPlan plan = bank.locked(player) ? noInterest(tier.plan()) : tier.plan();
        return new Beat(player.getUniqueId(), elapsed, active, plan, tier.maxBalance());
    }

    /** Locked by the alt-account limit: time still counts (so nothing piles up), but nothing is paid. */
    private static InterestPlan noInterest(InterestPlan plan) {
        return plan.withTier(BigDecimal.ZERO, BigDecimal.ZERO, null, null);
    }

    private void notify(Player player, String key, Payout payout) {
        bank.messages().send(player, key, Map.of("amount", bank.fmt(payout.amount()), "balance", bank.fmt(payout.balance()),
                "time", TimeText.format(Duration.ofMillis(payout.offlineMillis()))));
        Key sound = bank.settings().interest().sound();
        if (sound != null) player.playSound(Sound.sound(sound, Sound.Source.MASTER, 1f, 1f));
    }

    /** Remembers the new balance and tells other plugins (BankTransactionEvent). Interest thread. */
    private void paid(UUID uuid, Payout payout, String source) {
        bank.cacheBalance(uuid, payout.balance());
        bank.runOnMain(() -> bank.changed(uuid, dev.direk.dkbank.storage.TransactionType.INTEREST, payout.amount(),
                BigDecimal.ZERO, payout.balance(), null, source));
    }

    private boolean lastBeatContains(UUID uuid) {
        return Bukkit.getPlayer(uuid) != null; // they may have left while this was queued
    }

    /**
     * Online time until a player's next payout, from the last saved progress (no database). Any thread.
     *
     * @return milliseconds, or -1 if unknown (offline, or not loaded yet)
     */
    public long untilPayout(UUID uuid, long periodMillis) {
        long[] p = progress.get(uuid);
        if (p == null) return -1;
        long done = p[0] + Math.min(MAX_BEAT_MILLIS, Math.max(0, System.currentTimeMillis() - p[1]));
        return Math.max(1000, periodMillis - done);
    }

    // ------------------------------------------------------------------ /bank interest

    /**
     * A player's interest progress right now.
     *
     * @param unrecorded online time since the last heartbeat, not saved yet
     */
    public record Status(InterestPlan plan, InterestState state, long unrecorded) {

        /** The part of the balance earning interest now. */
        public BigDecimal earning() {
            return InterestMath.earningBase(state.base(), plan.cap());
        }

        /** Online time in the current payout cycle. */
        public long done() {
            return Math.min(plan.onlinePeriodMillis(), state.activeMillis() + state.afkMillis() + unrecorded);
        }

        /** Online time until the next payout (at least a second). */
        public long untilPayout() {
            return Math.max(1000, plan.onlinePeriodMillis() - done());
        }
    }

    /** Loads a player's interest progress; {@code done} gets null if the account can't be read. Main thread. */
    public void status(Player player, java.util.function.Consumer<@Nullable Status> done) {
        UUID uuid = player.getUniqueId();
        InterestPlan plan = planFor(player);
        Long last = lastBeat.get(uuid);
        long unrecorded = last == null ? 0 : Math.min(Math.max(0, System.currentTimeMillis() - last), MAX_BEAT_MILLIS);
        submit("read interest progress", () -> {
            InterestState state;
            try {
                state = store.interestState(uuid).orElse(null);
            } catch (RuntimeException e) {
                bank.runOnMain(() -> done.accept(null));
                throw e; // logged by submit
            }
            InterestState found = state;
            bank.runOnMain(() -> done.accept(found == null ? null : new Status(plan, found, unrecorded)));
        });
    }

    public void showInfo(Player player) {
        status(player, status -> {
            if (status == null) bank.messages().send(player, "error");
            else sendInfo(player, status);
        });
    }

    private void sendInfo(Player player, Status status) {
        InterestPlan plan = status.plan();
        Messages m = bank.messages();
        m.send(player, "interest.info.header", Map.of(), Map.of("tier", bank.tierOf(player).displayName()));
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
                "earning", bank.fmt(status.earning()),
                "cap", cap == null ? m.raw("interest.info.no-cap") : bank.fmt(cap)));
        if (plan.onlineEnabled() || plan.offlineEnabled()) {
            m.send(player, "interest.info.next", Map.of(
                    "time", TimeText.format(Duration.ofMillis(status.untilPayout())),
                    "done", TimeText.format(Duration.ofMillis(status.done())),
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
