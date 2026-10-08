package dev.direk.dkbank.bank;

import dev.direk.dkbank.api.event.BankPreTransactionEvent;
import dev.direk.dkbank.api.event.BankTransactionEvent;
import dev.direk.dkbank.api.event.TransactionKind;
import dev.direk.dkbank.config.Messages;
import dev.direk.dkbank.config.Settings;
import dev.direk.dkbank.economy.Wallet;
import dev.direk.dkbank.money.AmountInput;
import dev.direk.dkbank.money.Money;
import dev.direk.dkbank.storage.BankStore;
import dev.direk.dkbank.storage.StoreTypes;
import dev.direk.dkbank.storage.StoreTypes.Account;
import dev.direk.dkbank.storage.StoreTypes.Entry;
import dev.direk.dkbank.storage.StoreTypes.Failure;
import dev.direk.dkbank.storage.StoreTypes.Limits;
import dev.direk.dkbank.storage.StoreTypes.Page;
import dev.direk.dkbank.storage.StoreTypes.Result;
import dev.direk.dkbank.storage.StoreTypes.TransferResult;
import dev.direk.dkbank.storage.TransactionType;
import dev.direk.dkbank.tier.Tier;
import dev.direk.dkbank.tier.Tiers;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.net.InetAddress;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Moves money between wallets and bank accounts.
 * <p>
 * Threads: wallet (Vault) calls happen on the main thread; database work happens on dkBank's own worker
 * threads; results come back to the main thread through a queue that is drained every tick, and once
 * more during shutdown, so a refund is never lost when the server stops.
 * <p>
 * Order of every deposit: take from the wallet, then credit the bank; if the bank step fails, the wallet
 * is refunded. Every withdrawal: debit the bank, then pay the wallet; if the wallet refuses, the bank is
 * refunded. A player can't start a new operation while one is still running.
 */
public final class BankService {

    /** Told when a money operation is completely finished, and whether money moved. Main thread. */
    @FunctionalInterface
    public interface Outcome {
        Outcome NONE = success -> {
        };

        void finish(boolean success);
    }

    private final JavaPlugin plugin;
    private final BankStore store;
    private final Wallet wallet;
    private volatile Settings settings;
    private volatile Messages messages;
    private volatile Tiers tiers;

    private final ExecutorService workers;
    private final Queue<Runnable> mainQueue = new ConcurrentLinkedQueue<>();
    private final Set<UUID> busy = new HashSet<>(); // main thread only
    private final Map<UUID, BigDecimal> balances = new ConcurrentHashMap<>();
    private final AltGuard alts;
    /** When each cached account was loaded, so accounts of players who never joined (denied logins) are dropped. */
    private final Map<UUID, Long> loadedAt = new ConcurrentHashMap<>();
    private @Nullable BukkitTask sweepTask;
    /** Bought tier of loaded accounts ("" = the first tier). */
    private final Map<UUID, String> boughtTiers = new ConcurrentHashMap<>();
    /** Current tier of online players, bought or from a permission. Refreshed on the main thread. */
    private final Map<UUID, String> onlineTiers = new ConcurrentHashMap<>();
    private @Nullable BukkitTask drainTask;

    public BankService(JavaPlugin plugin, BankStore store, Wallet wallet, Settings settings, Messages messages,
                       Tiers tiers, int threads) {
        this.plugin = plugin;
        this.store = store;
        this.wallet = wallet;
        this.settings = settings;
        this.messages = messages;
        this.tiers = tiers;
        this.alts = new AltGuard(store, () -> this.settings, plugin.getLogger());
        AtomicInteger counter = new AtomicInteger();
        this.workers = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "dkBank-worker-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        drainTask = Bukkit.getScheduler().runTaskTimer(plugin, this::drain, 1L, 1L);
        sweepTask = Bukkit.getScheduler().runTaskTimer(plugin, this::sweep, 1200L, 1200L);
    }

    /** Finishes every operation in progress (including refunds) before returning. */
    public void shutdown() {
        if (sweepTask != null) sweepTask.cancel();
        workers.shutdown();
        try {
            if (!workers.awaitTermination(20, TimeUnit.SECONDS)) {
                plugin.getLogger().severe("Some bank operations didn't finish within 20 seconds of shutdown. "
                        + "Stopping them; anything listed below may need checking by hand.");
                List<Runnable> unstarted = workers.shutdownNow();
                if (!unstarted.isEmpty()) plugin.getLogger().severe(unstarted.size() + " bank operations never started.");
                workers.awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        drain(); // results and refunds still waiting; bank refunds now run right here (the workers are stopped)
        if (drainTask != null) drainTask.cancel();
    }

    /** Drops cached accounts of players who aren't online (left mid-operation, or their login was denied). */
    private void sweep() {
        long cutoff = System.currentTimeMillis() - 60_000L;
        for (UUID uuid : List.copyOf(loadedAt.keySet())) {
            Long at = loadedAt.get(uuid);
            if (at != null && at < cutoff && Bukkit.getPlayer(uuid) == null) unload(uuid);
        }
    }

    public void reload(Settings settings, Messages messages, Tiers tiers) {
        this.settings = settings;
        this.messages = messages;
        this.tiers = tiers;
        for (Player player : Bukkit.getOnlinePlayers()) tierOf(player);
    }

    public Tiers tiers() {
        return tiers;
    }

    public AltGuard alts() {
        return alts;
    }

    /** Whether the alt-account limit locks this player's bank. Main thread. */
    public boolean locked(Player player) {
        return alts.isLocked(player);
    }

    /** Tells a locked player why. */
    void sendLocked(CommandSender to) {
        send(to, "alts.locked", vars("max", String.valueOf(settings.alts().maxPerAddress())));
    }

    public BankStore store() {
        return store;
    }

    public Settings settings() {
        return settings;
    }

    public Messages messages() {
        return messages;
    }

    public Wallet wallet() {
        return wallet;
    }

    // ------------------------------------------------------------------ accounts

    /** Called on the login thread: creates the account if needed and caches the balance. */
    public void loadAccount(UUID uuid, String name) {
        loadAccount(uuid, name, null);
    }

    /** Like {@link #loadAccount(UUID, String)}, also checking the alt-account limit for the login address. */
    public void loadAccount(UUID uuid, String name, @Nullable InetAddress address) {
        try {
            remember(store.ensureAccount(uuid, name));
            if (address != null) alts.check(uuid, address);
        } catch (BankStore.StorageException e) {
            plugin.getLogger().log(Level.SEVERE, "Couldn't load " + name + "'s bank account", e);
        }
    }

    public void unload(UUID uuid) {
        balances.remove(uuid);
        boughtTiers.remove(uuid);
        onlineTiers.remove(uuid);
        loadedAt.remove(uuid);
        alts.forget(uuid);
    }

    private void remember(Account account) {
        balances.put(account.uuid(), account.balance());
        boughtTiers.put(account.uuid(), account.tier() == null ? "" : account.tier());
        loadedAt.put(account.uuid(), System.currentTimeMillis());
    }

    // ------------------------------------------------------------------ tiers

    /** A player's current tier: the bought one, or a higher one from a permission. Main thread. */
    public Tier tierOf(Player player) {
        Tier tier = tiers.resolve(boughtTiers.get(player.getUniqueId()), player::hasPermission);
        onlineTiers.put(player.getUniqueId(), tier.id());
        return tier;
    }

    /**
     * A loaded account's tier without checking permissions: the online player's tier as last worked out,
     * or the bought tier. Any thread (e.g. placeholders).
     */
    public Tier tierFor(UUID uuid) {
        Tiers t = tiers;
        String online = onlineTiers.get(uuid);
        if (online != null) {
            Optional<Tier> tier = t.byId(online);
            if (tier.isPresent()) return tier.get();
        }
        return t.bought(boughtTier(uuid));
    }

    /** @return the bought tier of a loaded account, or null for the first tier or if not loaded */
    public @Nullable String boughtTier(UUID uuid) {
        String tier = boughtTiers.get(uuid);
        return tier == null || tier.isEmpty() ? null : tier;
    }

    /** Remembers a changed bought tier, and refreshes the player's current tier if they're online. */
    public void cacheBoughtTier(UUID uuid, @Nullable String tier) {
        if (!balances.containsKey(uuid)) return;
        boughtTiers.put(uuid, tier == null ? "" : tier);
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && Bukkit.isPrimaryThread()) tierOf(player);
    }

    /** The tier that limits an account: the online player's current tier, or else the bought tier. Any thread. */
    Tier tierOf(Account account) {
        Tiers t = tiers;
        String online = onlineTiers.get(account.uuid());
        if (online != null) {
            Optional<Tier> tier = t.byId(online);
            if (tier.isPresent()) return tier.get();
        }
        return t.bought(account.tier());
    }

    /** Updates the cached balance of a loaded account (e.g. after interest). Any thread. */
    public void cacheBalance(UUID uuid, BigDecimal balance) {
        balances.computeIfPresent(uuid, (k, v) -> balance);
    }

    /** Runs {@code task} on the main thread at the next tick (or during shutdown). Any thread. */
    public void runOnMain(Runnable task) {
        mainQueue.add(task);
    }

    /** @return the cached balance of an online player, or null if not loaded */
    public @Nullable BigDecimal cachedBalance(UUID uuid) {
        return balances.get(uuid);
    }

    // ------------------------------------------------------------------ deposit

    public void deposit(Player player, AmountInput input) {
        deposit(player, input, Outcome.NONE);
    }

    /** @param done told whether the money moved, once everything is finished (main thread) */
    public void deposit(Player player, AmountInput input, Outcome done) {
        if (!startDeposit(player, input, done)) done.finish(false);
    }

    private boolean startDeposit(Player player, AmountInput input, Outcome done) {
        if (!economyReady(player) || isBusy(player)) return false;
        if (locked(player)) {
            sendLocked(player);
            return false;
        }
        Settings s = settings;
        UUID uuid = player.getUniqueId();

        BigDecimal walletBalance = wallet.balance(player);
        BigDecimal amount = input.resolve(walletBalance);
        BigDecimal max = tierOf(player).maxBalance();
        BigDecimal cached = balances.get(uuid);
        if (input.isShare()) {
            // "all" fills up to the limits instead of failing
            if (max != null && cached != null) amount = Money.floor(amount.min(max.subtract(cached).max(Money.ZERO)));
            if (s.maxPerTransaction().signum() > 0) amount = amount.min(s.maxPerTransaction());
        }

        if (amount.signum() <= 0) {
            boolean full = max != null && cached != null && cached.compareTo(max) >= 0;
            send(player, full ? "bank-full" : "not-enough-wallet", vars("wallet", fmt(walletBalance), "room", fmt(Money.ZERO)));
            return false;
        }
        if (amount.compareTo(s.minAmount()) < 0) {
            send(player, "amount-too-small", vars("min", fmt(s.minAmount())));
            return false;
        }
        if (s.overTransactionLimit(amount)) {
            send(player, "amount-too-large", vars("max", fmt(s.maxPerTransaction())));
            return false;
        }
        if (amount.compareTo(walletBalance) > 0) {
            send(player, "not-enough-wallet", vars("wallet", fmt(walletBalance)));
            return false;
        }
        if (max != null && cached != null && cached.add(amount).compareTo(max) > 0) {
            send(player, "bank-full", vars("room", fmt(max.subtract(cached).max(Money.ZERO)), "limit", fmt(max)));
            return false;
        }
        if (!allowed(player, BankPreTransactionEvent.Action.DEPOSIT, amount, null)) return false;
        if (!wallet.take(player, amount)) {
            send(player, "wallet-error");
            return false;
        }

        BigDecimal deposited = amount;
        String name = player.getName();
        long startedAt = System.currentTimeMillis() - 1000;
        busy.add(uuid);
        async(() -> {
            ensure(uuid, name);
            return store.credit(uuid, deposited, TransactionType.DEPOSIT, max, null);
        }, result -> {
            busy.remove(uuid);
            if (result.ok()) {
                cacheBalance(uuid, result.balance());
                send(player, "deposit-success", vars("amount", fmt(deposited), "balance", fmt(result.balance())));
                changed(uuid, TransactionType.DEPOSIT, deposited, Money.ZERO, result.balance(), null, null);
                done.finish(true);
            } else {
                refundWallet(uuid, deposited);
                send(player, result.failure() == Failure.BALANCE_LIMIT ? "bank-full" : "error",
                        vars("room", fmt(max == null ? Money.ZERO : max.subtract(result.available()).max(Money.ZERO)),
                                "limit", fmt(max == null ? Money.HARD_MAX : max)));
                done.finish(false);
            }
        }, error -> {
            busy.remove(uuid);
            fail(player, "deposit", error);
            if (error instanceof BankStore.CommitUncertainException) {
                // It may have been saved: only refund the wallet if it wasn't.
                long since = startedAt;
                async(() -> store.lastLogged(uuid, TransactionType.DEPOSIT, since), saved -> {
                    if (saved.isPresent() && saved.get().amount().compareTo(deposited) == 0) {
                        plugin.getLogger().warning("The deposit of " + deposited.toPlainString() + " by " + name
                                + " was saved after all; no refund needed.");
                    } else {
                        refundWallet(uuid, deposited);
                    }
                }, check -> plugin.getLogger().log(Level.SEVERE, "CHECK BY HAND: couldn't tell whether " + name
                        + "'s deposit of " + deposited.toPlainString() + " was saved. If it isn't in their /bank "
                        + "admin history, give it back to their wallet.", check));
            } else {
                refundWallet(uuid, deposited);
            }
            done.finish(false);
        });
        return true;
    }

    // ------------------------------------------------------------------ withdraw

    public void withdraw(Player player, AmountInput input) {
        withdraw(player, input, Outcome.NONE);
    }

    /** @param done told whether the money moved, once everything is finished (main thread) */
    public void withdraw(Player player, AmountInput input, Outcome done) {
        if (!startWithdraw(player, input, done)) done.finish(false);
    }

    private boolean startWithdraw(Player player, AmountInput input, Outcome done) {
        if (!economyReady(player) || isBusy(player)) return false;
        if (locked(player) && !settings.alts().allowWithdraw()) {
            sendLocked(player);
            return false;
        }
        Settings s = settings;
        if (!checkFixedAmount(player, input, s)) return false;
        if (!allowed(player, BankPreTransactionEvent.Action.WITHDRAW, input.isShare() ? null : input.fixed(), null)) return false;

        UUID uuid = player.getUniqueId();
        String name = player.getName();
        BigDecimal fee = s.withdrawFeePercent();
        Limits limits = limits(s);
        long startedAt = System.currentTimeMillis() - 1000;
        busy.add(uuid);
        async(() -> {
            ensure(uuid, name);
            return store.debit(uuid, input, fee, TransactionType.WITHDRAW, null, limits);
        }, result -> {
            busy.remove(uuid);
            if (!result.ok()) {
                sendFailure(player, result, s);
                done.finish(false);
                return;
            }
            cacheBalance(uuid, result.balance());
            changed(uuid, TransactionType.WITHDRAW, result.amount(), result.fee(), result.balance(), null, null);
            BigDecimal received = result.amount().subtract(result.fee());
            boolean paid;
            try {
                paid = wallet.give(Bukkit.getOfflinePlayer(uuid), received);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "The economy plugin failed to pay a withdrawal into a wallet", e);
                paid = false;
            }
            if (paid) {
                send(player, result.fee().signum() > 0 ? "withdraw-success-fee" : "withdraw-success",
                        vars("amount", fmt(result.amount()), "fee", fmt(result.fee()), "received", fmt(received),
                                "balance", fmt(result.balance())));
                done.finish(true);
            } else {
                // The wallet refused the money: put all of it (fee included) back in the bank.
                refundBank(uuid, result.amount());
                send(player, "wallet-error");
                done.finish(false);
            }
        }, error -> {
            busy.remove(uuid);
            fail(player, "withdraw", error);
            if (error instanceof BankStore.CommitUncertainException) {
                // If the withdrawal was saved after all, the player still gets the money.
                async(() -> store.lastLogged(uuid, TransactionType.WITHDRAW, startedAt), saved -> {
                    if (saved.isEmpty()) return;
                    BigDecimal owed = saved.get().amount().subtract(saved.get().fee());
                    if (!wallet.give(Bukkit.getOfflinePlayer(uuid), owed)) refundBank(uuid, saved.get().amount());
                    plugin.getLogger().warning("The withdrawal by " + name + " was saved after all; paid "
                            + owed.toPlainString() + " to their wallet.");
                }, check -> plugin.getLogger().log(Level.SEVERE, "CHECK BY HAND: couldn't tell whether " + name
                        + "'s withdrawal was saved. Compare their /bank admin history with their wallet.", check));
            }
            done.finish(false);
        });
        return true;
    }

    // ------------------------------------------------------------------ transfer

    private enum PayStatus { OK, UNKNOWN, SELF }

    private record PayOutcome(PayStatus status, @Nullable TransferResult transfer, @Nullable UUID receiver) {
    }

    public void pay(Player player, String targetName, AmountInput input) {
        pay(player, targetName, input, Outcome.NONE);
    }

    /** @param done told whether the money moved, once everything is finished (main thread) */
    public void pay(Player player, String targetName, AmountInput input, Outcome done) {
        if (!startPay(player, targetName, input, done)) done.finish(false);
    }

    private boolean startPay(Player player, String targetName, AmountInput input, Outcome done) {
        Settings s = settings;
        if (!s.transfersEnabled()) {
            send(player, "transfers-disabled");
            return false;
        }
        if (isBusy(player) || !checkFixedAmount(player, input, s)) return false;
        if (locked(player)) {
            sendLocked(player);
            return false;
        }
        if (!s.offlineTransfers() && Bukkit.getPlayerExact(targetName) == null) {
            send(player, "target-offline", vars("player", targetName));
            return false;
        }
        if (!allowed(player, BankPreTransactionEvent.Action.TRANSFER, input.isShare() ? null : input.fixed(), targetName)) {
            return false;
        }

        UUID uuid = player.getUniqueId();
        String name = player.getName();
        BigDecimal fee = s.transferFeePercent();
        Limits limits = limits(s);
        busy.add(uuid);
        async(() -> {
            ensure(uuid, name);
            Optional<Account> receiver = store.accountByName(targetName);
            if (receiver.isEmpty()) return new PayOutcome(PayStatus.UNKNOWN, null, null);
            if (receiver.get().uuid().equals(uuid)) return new PayOutcome(PayStatus.SELF, null, null);
            return new PayOutcome(PayStatus.OK, store.transfer(uuid, receiver.get().uuid(), input, fee,
                    account -> tierOf(account).maxBalance(), limits),
                    receiver.get().uuid());
        }, outcome -> {
            busy.remove(uuid);
            if (outcome.status() == PayStatus.UNKNOWN) {
                send(player, "unknown-player", vars("player", targetName));
                done.finish(false);
                return;
            }
            if (outcome.status() == PayStatus.SELF || outcome.transfer() == null || outcome.receiver() == null) {
                send(player, "cannot-pay-self");
                done.finish(false);
                return;
            }
            TransferResult t = outcome.transfer();
            Result r = t.sender();
            if (!r.ok()) {
                if (r.failure() == Failure.BALANCE_LIMIT) send(player, "receiver-full", vars("player", t.receiverName()));
                else sendFailure(player, r, s);
                done.finish(false);
                return;
            }
            cacheBalance(uuid, r.balance());
            cacheBalance(outcome.receiver(), t.receiverBalance());
            changed(uuid, TransactionType.TRANSFER_OUT, r.amount(), r.fee(), r.balance(), outcome.receiver(), null);
            changed(outcome.receiver(), TransactionType.TRANSFER_IN, r.amount(), Money.ZERO, t.receiverBalance(), uuid, null);
            send(player, r.fee().signum() > 0 ? "pay-sent-fee" : "pay-sent", vars("amount", fmt(r.amount()),
                    "fee", fmt(r.fee()), "player", t.receiverName(), "balance", fmt(r.balance())));
            Player receiver = Bukkit.getPlayer(outcome.receiver());
            if (receiver != null) {
                send(receiver, "pay-received", vars("amount", fmt(r.amount()), "player", name,
                        "balance", fmt(t.receiverBalance())));
            }
            done.finish(true);
        }, error -> {
            busy.remove(uuid);
            fail(player, "transfer", error);
            done.finish(false);
        });
        return true;
    }

    // ------------------------------------------------------------------ balance and history

    public void showOwnBalance(Player player) {
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        async(() -> {
            ensure(uuid, name);
            return store.account(uuid).map(Account::balance).orElse(Money.ZERO);
        }, balance -> {
            cacheBalance(uuid, balance);
            messages.send(player, "balance", vars("balance", fmt(balance), "wallet", fmt(wallet.balance(player))),
                    Map.of("tier", tierOf(player).displayName()));
        }, error -> fail(player, "balance", error));
    }

    public void showBalance(CommandSender sender, String targetName) {
        async(() -> store.accountByName(targetName), account -> {
            if (account.isEmpty()) {
                send(sender, "unknown-player", vars("player", targetName));
            } else {
                send(sender, "balance-other", vars("player", account.get().name(), "balance", fmt(account.get().balance())));
            }
        }, error -> fail(sender, "balance", error));
    }

    public void showHistory(Player player, int page) {
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        int size = settings.historyPageSize();
        async(() -> {
            ensure(uuid, name);
            return store.history(uuid, page, size);
        }, result -> renderHistory(player, name, result, "/bank history"), error -> fail(player, "history", error));
    }

    public void showHistory(CommandSender sender, String targetName, int page) {
        int size = settings.historyPageSize();
        async(() -> store.accountByName(targetName).map(a -> Map.entry(a.name(), store.history(a.uuid(), page, size))),
                result -> {
                    if (result.isEmpty()) {
                        send(sender, "unknown-player", vars("player", targetName));
                        return;
                    }
                    String name = result.get().getKey();
                    renderHistory(sender, name, result.get().getValue(), "/bank admin history " + name);
                }, error -> fail(sender, "history", error));
    }

    private void renderHistory(Audience to, String owner, Page page, String command) {
        Messages m = messages;
        if (page.entries().isEmpty()) {
            m.send(to, "history.empty", vars("player", owner));
            return;
        }
        m.send(to, "history.header", vars("player", owner, "page", String.valueOf(page.page()),
                "pages", String.valueOf(page.pages())));
        for (Entry e : page.entries()) {
            String type = e.type().name().toLowerCase(Locale.ROOT).replace('_', '-');
            Map<String, String> v = vars("amount", fmt(e.amount()), "fee", fmt(e.fee()), "balance", fmt(e.balanceAfter()),
                    "player", e.otherName() == null ? "?" : e.otherName(), "actor", e.actor() == null ? "?" : e.actor(),
                    "date", settings.dateFormat().format(Instant.ofEpochMilli(e.time())));
            String text = m.raw("history.types." + type);
            if (text.isEmpty()) text = type; // a type missing from messages.yml still shows something
            Map<String, String> tier = Map.of("tier", e.otherName() == null ? tiers.first().displayName()
                    : tiers.byId(e.otherName()).map(Tier::displayName).orElse(e.otherName()));
            to.sendMessage(m.renderText(m.raw("history.entry").replace("<line>", text), v, tier));
        }
        if (page.pages() > 1) {
            Component footer = m.render("history.footer", vars("page", String.valueOf(page.page()),
                    "pages", String.valueOf(page.pages())));
            if (page.page() > 1) {
                footer = m.render("history.previous", Map.of())
                        .clickEvent(ClickEvent.runCommand(command + " " + (page.page() - 1)))
                        .append(Component.space()).append(footer);
            }
            if (page.page() < page.pages()) {
                footer = footer.append(Component.space()).append(m.render("history.next", Map.of())
                        .clickEvent(ClickEvent.runCommand(command + " " + (page.page() + 1))));
            }
            to.sendMessage(footer);
        }
    }

    // ------------------------------------------------------------------ admin

    public void adminGive(CommandSender sender, String targetName, BigDecimal amount) {
        String actor = sender.getName();
        async(() -> store.accountByName(targetName)
                        .map(a -> Map.entry(a, store.credit(a.uuid(), amount, TransactionType.ADMIN_GIVE, null, actor))),
                result -> adminResult(sender, targetName, result, "admin.give", amount, TransactionType.ADMIN_GIVE), error -> fail(sender, "give", error));
    }

    public void adminTake(CommandSender sender, String targetName, BigDecimal amount) {
        String actor = sender.getName();
        AmountInput input = new AmountInput(amount, null);
        async(() -> store.accountByName(targetName)
                        .map(a -> Map.entry(a, store.debit(a.uuid(), input, BigDecimal.ZERO, TransactionType.ADMIN_TAKE, actor))),
                result -> adminResult(sender, targetName, result, "admin.take", amount, TransactionType.ADMIN_TAKE), error -> fail(sender, "take", error));
    }

    public void adminSet(CommandSender sender, String targetName, BigDecimal amount) {
        String actor = sender.getName();
        async(() -> store.accountByName(targetName).map(a -> Map.entry(a, store.set(a.uuid(), amount, actor))),
                result -> adminResult(sender, targetName, result, "admin.set", amount, TransactionType.ADMIN_SET), error -> fail(sender, "set", error));
    }

    private void adminResult(CommandSender sender, String targetName, Optional<Map.Entry<Account, Result>> result,
                             String key, BigDecimal amount, TransactionType type) {
        if (result.isEmpty()) {
            send(sender, "unknown-player", vars("player", targetName));
            return;
        }
        Account account = result.get().getKey();
        Result r = result.get().getValue();
        if (!r.ok()) {
            if (r.failure() == Failure.INSUFFICIENT_FUNDS) {
                send(sender, "admin.take-insufficient", vars("player", account.name(), "balance", fmt(r.available())));
            } else {
                send(sender, "admin.limit", vars("player", account.name()));
            }
            return;
        }
        cacheBalance(account.uuid(), r.balance());
        changed(account.uuid(), type, r.amount(), Money.ZERO, r.balance(), null, sender.getName());
        send(sender, key, vars("player", account.name(), "amount", fmt(amount), "balance", fmt(r.balance())));
    }

    // ------------------------------------------------------------------ alt accounts (admin)

    private record AltView(Account account, List<StoreTypes.AltAccount> onAddress) {
    }

    /** Lists the accounts that share the player's most recent connection, and which are locked. */
    public void adminAlts(CommandSender sender, String targetName) {
        Settings s = settings;
        long since = System.currentTimeMillis() - s.alts().windowMillis();
        async(() -> store.accountByName(targetName).map(a -> new AltView(a,
                        store.latestIp(a.uuid()).map(ip -> store.accountsOnIp(ip, since)).orElse(List.of()))),
                view -> {
                    if (view.isEmpty()) {
                        send(sender, "unknown-player", vars("player", targetName));
                        return;
                    }
                    List<StoreTypes.AltAccount> accounts = view.get().onAddress();
                    String name = view.get().account().name();
                    if (accounts.isEmpty() || !s.alts().enabled()) {
                        send(sender, "admin.alts-none", vars("player", name));
                        return;
                    }
                    send(sender, "admin.alts-header", vars("player", name, "count", String.valueOf(accounts.size()),
                            "max", String.valueOf(s.alts().maxPerAddress()),
                            "window", dev.direk.dkbank.util.TimeText.format(java.time.Duration.ofMillis(s.alts().windowMillis()))));
                    for (StoreTypes.AltAccount a : accounts) {
                        String status = a.exempt() ? "admin.alts-status-exempt"
                                : AltRule.overLimit(accounts, a.uuid(), s.alts().maxPerAddress()) ? "admin.alts-status-locked"
                                : "admin.alts-status-allowed";
                        messages.send(sender, "admin.alts-entry", vars("name", a.name(),
                                        "date", s.dateFormat().format(Instant.ofEpochMilli(a.firstSeen()))),
                                Map.of("status", messages.raw(status)));
                    }
                }, error -> fail(sender, "alt list", error));
    }

    /** Allows an account whatever the alt limit, or makes it follow the limit again. */
    public void adminAltExempt(CommandSender sender, String targetName, boolean exempt) {
        Settings s = settings;
        long since = System.currentTimeMillis() - s.alts().windowMillis();
        async(() -> {
            Optional<Account> account = store.accountByName(targetName);
            if (account.isEmpty()) return Optional.<Account>empty();
            store.setAltExempt(account.get().uuid(), exempt);
            // An allowed account frees a place for the next one on the same connection: decide again for
            // everyone online there.
            store.latestIp(account.get().uuid()).ifPresent(ip -> {
                for (StoreTypes.AltAccount a : store.accountsOnIp(ip, since)) {
                    if (balances.containsKey(a.uuid())) alts.recheck(a.uuid());
                }
            });
            if (balances.containsKey(account.get().uuid())) alts.recheck(account.get().uuid());
            return account;
        }, account -> {
            if (account.isEmpty()) send(sender, "unknown-player", vars("player", targetName));
            else send(sender, exempt ? "admin.alts-allowed" : "admin.alts-reset", vars("player", account.get().name()));
        }, error -> fail(sender, "alt exemption", error));
    }

    // ------------------------------------------------------------------ helpers

    /** Always short: {@code $1.2M}. */
    public String fmtShort(BigDecimal amount) {
        return settings.format().compact(amount);
    }

    /** Like {@link #fmt}, but safe off the main thread: never asks the economy plugin (placeholders). */
    public String fmtAnyThread(BigDecimal amount) {
        return Bukkit.isPrimaryThread() ? fmt(amount) : settings.format().display(amount);
    }

    public String fmt(BigDecimal amount) {
        if (settings.useEconomyFormat()) {
            String formatted = wallet.format(amount);
            if (formatted != null) return formatted;
        }
        return settings.format().display(amount);
    }

    private static Limits limits(Settings s) {
        return new Limits(s.minAmount(), s.maxPerTransaction().signum() > 0 ? s.maxPerTransaction() : null);
    }

    /** Checks a typed amount against the min/max before doing anything. "all"/"half" are checked later. */
    private boolean checkFixedAmount(Player player, AmountInput input, Settings s) {
        if (input.isShare() || input.fixed() == null) return true;
        if (input.fixed().compareTo(s.minAmount()) < 0) {
            send(player, "amount-too-small", vars("min", fmt(s.minAmount())));
            return false;
        }
        if (s.overTransactionLimit(input.fixed())) {
            send(player, "amount-too-large", vars("max", fmt(s.maxPerTransaction())));
            return false;
        }
        return true;
    }

    private void sendFailure(Player player, Result r, Settings s) {
        Failure f = r.failure();
        if (f == Failure.BELOW_MINIMUM) send(player, "amount-too-small", vars("min", fmt(s.minAmount())));
        else if (f == Failure.NO_ACCOUNT) send(player, "error");
        else send(player, "not-enough-bank", vars("balance", fmt(r.available())));
    }

    private boolean economyReady(Player player) {
        if (wallet.available()) return true;
        send(player, "economy-missing");
        return false;
    }

    void markBusy(UUID uuid) {
        busy.add(uuid);
    }

    void clearBusy(UUID uuid) {
        busy.remove(uuid);
    }

    boolean isBusy(Player player) {
        if (!busy.contains(player.getUniqueId())) return false;
        send(player, "busy");
        return true;
    }

    /** Creates the acting player's account if the login step missed it (worker thread). */
    void ensure(UUID uuid, String name) {
        if (!balances.containsKey(uuid)) remember(store.ensureAccount(uuid, name));
    }

    private void refundWallet(UUID uuid, BigDecimal amount) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
        boolean refunded;
        try {
            refunded = wallet.give(player, amount);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "The economy plugin failed during a refund", e);
            refunded = false;
        }
        if (!refunded) {
            plugin.getLogger().severe("REFUND FAILED: couldn't return " + amount.toPlainString() + " to the wallet of "
                    + player.getName() + " (" + uuid + "). Give it back manually.");
        }
    }

    private void refundBank(UUID uuid, BigDecimal amount) {
        if (workers.isShutdown()) { // stopping: do it right now instead of queueing it
            try {
                StoreTypes.Result result = store.credit(uuid, amount, TransactionType.REFUND, null, "dkBank");
                if (!result.ok()) throw new IllegalStateException(String.valueOf(result.failure()));
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "REFUND FAILED: couldn't return " + amount.toPlainString()
                        + " to bank account " + uuid + ". Give it back manually.", e);
            }
            return;
        }
        async(() -> store.credit(uuid, amount, TransactionType.REFUND, null, "dkBank"), result -> {
            if (result.ok()) {
                balances.computeIfPresent(uuid, (k, v) -> result.balance());
                changed(uuid, TransactionType.REFUND, amount, Money.ZERO, result.balance(), null, "dkBank");
            } else plugin.getLogger().severe("REFUND FAILED: couldn't return " + amount.toPlainString() + " to bank account " + uuid);
        }, error -> plugin.getLogger().log(Level.SEVERE, "REFUND FAILED: couldn't return " + amount.toPlainString()
                + " to bank account " + uuid + ". Give it back manually.", error));
    }

    // ------------------------------------------------------------------ events

    /** Fires {@link BankPreTransactionEvent}. @return false if a plugin cancelled it (they've been told) */
    private boolean allowed(Player player, BankPreTransactionEvent.Action action, @Nullable BigDecimal amount,
                            @Nullable String target) {
        BankPreTransactionEvent event = new BankPreTransactionEvent(player, action, amount, target);
        Bukkit.getPluginManager().callEvent(event);
        if (!event.isCancelled()) return true;
        if (event.getCancelMessage() != null) player.sendMessage(event.getCancelMessage());
        return false;
    }

    /** Fires {@link BankTransactionEvent} for a change that's saved. Main thread. */
    public void changed(UUID account, TransactionType type, BigDecimal amount, BigDecimal fee, BigDecimal balance,
                        @Nullable UUID other, @Nullable String source) {
        TransactionKind kind;
        try {
            kind = TransactionKind.valueOf(type.name());
        } catch (IllegalArgumentException e) {
            return; // e.g. TIER_SET: not a money change
        }
        Bukkit.getPluginManager().callEvent(new BankTransactionEvent(account, kind, amount, fee, balance, other, source));
    }

    void fail(CommandSender sender, String what, Throwable error) {
        plugin.getLogger().log(Level.SEVERE, "Bank " + what + " for " + sender.getName() + " failed", error);
        send(sender, "error");
    }

    /** Runs a read on a worker thread and hands the result to {@code done} on the main thread; errors are logged. */
    public <T> void query(String what, Supplier<T> work, Consumer<T> done) {
        async(work, done, error -> plugin.getLogger().log(Level.SEVERE, "Couldn't " + what, error));
    }

    /** Runs {@code work} on a worker thread, then {@code done} (or {@code failed}) on the main thread. */
    <T> void async(Supplier<T> work, Consumer<T> done, Consumer<Throwable> failed) {
        try {
            workers.execute(() -> {
                try {
                    T result = work.get();
                    mainQueue.add(() -> done.accept(result));
                } catch (Throwable t) {
                    mainQueue.add(() -> failed.accept(t));
                }
            });
        } catch (RejectedExecutionException e) {
            failed.accept(e); // shutting down
        }
    }

    private void drain() {
        Runnable next;
        while ((next = mainQueue.poll()) != null) {
            try {
                next.run();
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Error while finishing a bank operation", e);
            }
        }
    }

    void send(CommandSender to, String key) {
        messages.send(to, key, Map.of());
    }

    void send(CommandSender to, String key, Map<String, String> vars) {
        messages.send(to, key, vars);
    }

    static Map<String, String> vars(String... pairs) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
        return map;
    }
}
