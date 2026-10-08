package dev.direk.dkbank.bank;

import dev.direk.dkbank.api.BankAccount;
import dev.direk.dkbank.api.BankResult;
import dev.direk.dkbank.api.BankTier;
import dev.direk.dkbank.api.DkBankAPI;
import dev.direk.dkbank.api.LeaderboardEntry;
import dev.direk.dkbank.api.event.BankTierChangeEvent;
import dev.direk.dkbank.money.AmountInput;
import dev.direk.dkbank.money.Money;
import dev.direk.dkbank.storage.StoreTypes.Account;
import dev.direk.dkbank.storage.StoreTypes.Limits;
import dev.direk.dkbank.storage.StoreTypes.Result;
import dev.direk.dkbank.storage.StoreTypes.TopEntry;
import dev.direk.dkbank.storage.StoreTypes.TransferResult;
import dev.direk.dkbank.storage.TransactionType;
import dev.direk.dkbank.tier.Tier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * The Developer API, as other plugins see it through {@link DkBankAPI#get()}.
 */
public final class BankApiImpl implements DkBankAPI {

    private static final int SOURCE_LENGTH = 36;

    private final String version;
    private final BankService bank;
    private final Leaderboard leaderboard;

    public BankApiImpl(String version, BankService bank, Leaderboard leaderboard) {
        this.version = version;
        this.bank = bank;
        this.leaderboard = leaderboard;
    }

    @Override
    public String version() {
        return version;
    }

    // ------------------------------------------------------------------ helpers

    /** Runs {@code work} on a worker thread and completes the future on the main thread. */
    private <T> CompletableFuture<T> run(String what, Supplier<T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        bank.async(work, future::complete, error -> {
            Bukkit.getLogger().log(Level.SEVERE, "[dkBank] API call failed: " + what, error);
            future.completeExceptionally(error);
        });
        return future;
    }

    /** A finished result, still handed over on the main thread like every other. */
    private <T> CompletableFuture<T> now(T value) {
        CompletableFuture<T> future = new CompletableFuture<>();
        bank.runOnMain(() -> future.complete(value));
        return future;
    }

    /** Cents only, and within limits; null if it isn't a usable amount. */
    private static @Nullable BigDecimal clean(@Nullable BigDecimal amount) {
        if (amount == null) return null;
        BigDecimal floored = Money.floor(amount);
        if (floored.signum() <= 0 || floored.compareTo(Money.HARD_MAX) > 0) return null;
        return floored;
    }

    private static String source(String source) {
        String s = source.isBlank() ? "plugin" : source.trim();
        return s.length() > SOURCE_LENGTH ? s.substring(0, SOURCE_LENGTH) : s;
    }

    private BankResult invalid(UUID player) {
        BigDecimal cached = bank.cachedBalance(player);
        return new BankResult(BankResult.Failure.INVALID_AMOUNT, Money.ZERO, cached == null ? Money.ZERO : cached);
    }

    // ------------------------------------------------------------------ accounts

    @Override
    public CompletableFuture<Optional<BankAccount>> account(UUID player) {
        return run("read an account", () -> bank.store().account(player)
                .map(a -> new BankAccount(a.uuid(), a.name(), a.balance(), bank.tiers().bought(a.tier()).id())));
    }

    @Override
    public Optional<BigDecimal> cachedBalance(UUID player) {
        return Optional.ofNullable(bank.cachedBalance(player));
    }

    @Override
    public CompletableFuture<Boolean> has(UUID player, BigDecimal amount) {
        return run("check a balance", () -> bank.store().account(player)
                .map(a -> a.balance().compareTo(amount) >= 0).orElse(false));
    }

    // ------------------------------------------------------------------ money

    @Override
    public CompletableFuture<BankResult> give(UUID player, BigDecimal amount, String source) {
        BigDecimal value = clean(amount);
        if (value == null) return now(invalid(player));
        String by = source(source);
        CompletableFuture<BankResult> future = new CompletableFuture<>();
        bank.async(() -> {
            Optional<Account> account = bank.store().account(player);
            if (account.isEmpty()) return new BankResult(BankResult.Failure.NO_ACCOUNT, Money.ZERO, Money.ZERO);
            Result r = bank.store().credit(player, value, TransactionType.PLUGIN_GIVE,
                    bank.tierOf(account.get()).maxBalance(), by);
            return ApiTypes.result(r);
        }, result -> {
            if (result.success()) {
                bank.cacheBalance(player, result.balance());
                bank.changed(player, TransactionType.PLUGIN_GIVE, result.amount(), Money.ZERO, result.balance(), null, by);
            }
            future.complete(result);
        }, error -> fail(future, "give money", error));
        return future;
    }

    @Override
    public CompletableFuture<BankResult> take(UUID player, BigDecimal amount, String source) {
        BigDecimal value = clean(amount);
        if (value == null) return now(invalid(player));
        String by = source(source);
        CompletableFuture<BankResult> future = new CompletableFuture<>();
        bank.async(() -> ApiTypes.result(bank.store().debit(player, new AmountInput(value, null), BigDecimal.ZERO,
                TransactionType.PLUGIN_TAKE, by)), result -> {
            if (result.success()) {
                bank.cacheBalance(player, result.balance());
                bank.changed(player, TransactionType.PLUGIN_TAKE, result.amount(), Money.ZERO, result.balance(), null, by);
            }
            future.complete(result);
        }, error -> fail(future, "take money", error));
        return future;
    }

    @Override
    public CompletableFuture<BankResult> transfer(UUID from, UUID to, BigDecimal amount) {
        BigDecimal value = clean(amount);
        if (value == null) return now(invalid(from));
        if (from.equals(to)) {
            BigDecimal cached = bank.cachedBalance(from);
            return now(new BankResult(BankResult.Failure.SAME_ACCOUNT, Money.ZERO, cached == null ? Money.ZERO : cached));
        }
        CompletableFuture<BankResult> future = new CompletableFuture<>();
        bank.async(() -> bank.store().transfer(from, to, new AmountInput(value, null), BigDecimal.ZERO,
                account -> bank.tierOf(account).maxBalance(), Limits.NONE), (TransferResult t) -> {
            BankResult result = ApiTypes.result(t.sender());
            if (result.success()) {
                bank.cacheBalance(from, t.sender().balance());
                bank.cacheBalance(to, t.receiverBalance());
                bank.changed(from, TransactionType.TRANSFER_OUT, result.amount(), Money.ZERO, result.balance(), to, null);
                bank.changed(to, TransactionType.TRANSFER_IN, result.amount(), Money.ZERO, t.receiverBalance(), from, null);
            }
            future.complete(result);
        }, error -> fail(future, "transfer money", error));
        return future;
    }

    private void fail(CompletableFuture<BankResult> future, String what, Throwable error) {
        Bukkit.getLogger().log(Level.SEVERE, "[dkBank] API call failed: " + what, error);
        future.complete(new BankResult(BankResult.Failure.ERROR, Money.ZERO, Money.ZERO));
    }

    // ------------------------------------------------------------------ tiers

    @Override
    public List<BankTier> tiers() {
        List<BankTier> list = new ArrayList<>();
        for (Tier tier : bank.tiers().all()) list.add(ApiTypes.tier(tier));
        return List.copyOf(list);
    }

    @Override
    public Optional<BankTier> tier(String id) {
        return bank.tiers().byId(id).map(ApiTypes::tier);
    }

    @Override
    public BankTier tierOf(Player player) {
        return ApiTypes.tier(bank.tierOf(player));
    }

    @Override
    public CompletableFuture<Optional<BankTier>> boughtTier(UUID player) {
        return run("read a bought tier", () -> bank.store().account(player)
                .map(a -> ApiTypes.tier(bank.tiers().bought(a.tier()))));
    }

    @Override
    public CompletableFuture<Boolean> setTier(UUID player, @Nullable String tierId, String source) {
        Optional<Tier> tier = tierId == null ? Optional.of(bank.tiers().first()) : bank.tiers().byId(tierId);
        if (tier.isEmpty()) return now(false);
        String stored = tier.get().rank() == 0 ? null : tier.get().id();
        String by = source(source);
        return run("set a tier", () -> bank.store().setTier(player, stored, by).ok()).thenApply(ok -> {
            if (ok) {
                bank.cacheBoughtTier(player, stored);
                Bukkit.getPluginManager().callEvent(new BankTierChangeEvent(player, ApiTypes.tier(tier.get()),
                        BankTierChangeEvent.Cause.PLUGIN));
            }
            return ok;
        });
    }

    // ------------------------------------------------------------------ other

    @Override
    public boolean isLocked(Player player) {
        return bank.locked(player);
    }

    @Override
    public List<LeaderboardEntry> leaderboard() {
        List<TopEntry> entries = leaderboard.snapshot().entries();
        List<LeaderboardEntry> list = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            TopEntry e = entries.get(i);
            list.add(new LeaderboardEntry(i + 1, e.uuid(), e.name(), e.balance()));
        }
        return List.copyOf(list);
    }

    @Override
    public int rank(UUID player) {
        return leaderboard.snapshot().rank(player);
    }

    @Override
    public String format(BigDecimal amount) {
        return bank.fmt(amount);
    }
}
