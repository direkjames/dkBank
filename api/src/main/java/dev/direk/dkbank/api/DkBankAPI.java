package dev.direk.dkbank.api;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Entry point of the dkBank Developer API.
 * <p>
 * Get it once dkBank is enabled (add {@code depend: [dkBank]} or {@code softdepend: [dkBank]} to your
 * plugin.yml):
 * <pre>{@code
 * DkBankAPI bank = DkBankAPI.get();
 * bank.give(player.getUniqueId(), new BigDecimal("500"), "DailyRewards").thenAccept(result -> {
 *     if (result.success()) player.sendMessage("500 added to your bank!");
 * });
 * }</pre>
 *
 * <h2>Threads</h2>
 * Methods returning a {@link CompletableFuture} do their database work in the background and complete
 * the future <b>on the server's main thread</b>, so you can use the Bukkit API in {@code thenAccept}.
 * They can be called from any thread. Never call {@code join()} or {@code get()} on them from the main
 * thread: that would wait for the main thread itself and freeze the server.
 * <p>
 * Methods that return a value directly read memory only (cached balances, tiers, the last leaderboard) and
 * are safe to call often.
 *
 * <h2>Money</h2>
 * Amounts are {@link BigDecimal}s with two decimals. Amounts with more decimals are cut to the cent (never
 * rounded up). Every change is one database transaction and appears in the player's history.
 *
 * <h2>Events</h2>
 * See the {@code dev.direk.dkbank.api.event} package. All events are fired on the main thread.
 * <p>
 * Nothing in this API returns or accepts {@code null} unless marked {@code @Nullable}. Methods are only
 * ever added to this interface, never removed without a deprecation period.
 */
public interface DkBankAPI {

    /**
     * @return the running dkBank version, e.g. {@code 1.0.0}
     */
    String version();

    // ------------------------------------------------------------------ accounts

    /**
     * Loads an account from the database.
     *
     * @return the account, or empty if the player has never joined
     */
    CompletableFuture<Optional<BankAccount>> account(UUID player);

    /**
     * The balance of an online player, from memory. It's updated after every change dkBank makes.
     *
     * @return the balance, or empty if the account isn't loaded (the player is offline)
     */
    Optional<BigDecimal> cachedBalance(UUID player);

    /**
     * @return whether the account has at least {@code amount} in the bank (false if it doesn't exist)
     */
    CompletableFuture<Boolean> has(UUID player, BigDecimal amount);

    // ------------------------------------------------------------------ money

    /**
     * Adds money to a bank account, e.g. a reward. Respects the account's maximum balance (its tier).
     * Shows in the history as added by {@code source}.
     *
     * @param source who's adding it, e.g. your plugin's name (up to 36 characters are kept)
     */
    CompletableFuture<BankResult> give(UUID player, BigDecimal amount, String source);

    /**
     * Takes money from a bank account, e.g. a purchase. Fails with
     * {@link BankResult.Failure#INSUFFICIENT_FUNDS} if there isn't enough.
     *
     * @param source who's taking it, e.g. your plugin's name (up to 36 characters are kept)
     */
    CompletableFuture<BankResult> take(UUID player, BigDecimal amount, String source);

    /**
     * Moves money from one bank account to another, without fees. Respects the receiver's maximum
     * balance. The result is the sender's side.
     */
    CompletableFuture<BankResult> transfer(UUID from, UUID to, BigDecimal amount);

    // ------------------------------------------------------------------ tiers

    /**
     * @return every tier, lowest first; the first is everyone's starting tier
     */
    List<BankTier> tiers();

    /**
     * @return the tier with this id (the name in tiers.yml), or empty
     */
    Optional<BankTier> tier(String id);

    /**
     * An online player's tier, including tiers from permissions. Main thread.
     */
    BankTier tierOf(Player player);

    /**
     * The tier an account bought (or was given by staff), without permission tiers. Works offline.
     *
     * @return the tier, or empty if the account doesn't exist
     */
    CompletableFuture<Optional<BankTier>> boughtTier(UUID player);

    /**
     * Sets the tier an account has bought, free of charge (e.g. a crate reward). Permission tiers still
     * apply on top.
     *
     * @param tierId a tier id, or null for the first tier
     * @param source who's changing it (shown in the history)
     * @return false if the account or the tier doesn't exist
     */
    CompletableFuture<Boolean> setTier(UUID player, @Nullable String tierId, String source);

    // ------------------------------------------------------------------ other

    /**
     * @return whether the alt-account limit locks this player's bank. Main thread.
     */
    boolean isLocked(Player player);

    /**
     * The leaderboard as last worked out (every few minutes), richest first.
     */
    List<LeaderboardEntry> leaderboard();

    /**
     * @return a player's place on the leaderboard (1 = richest), or 0 if they aren't on it
     */
    int rank(UUID player);

    /**
     * Formats an amount the way dkBank shows money, e.g. {@code $1,234.56}.
     */
    String format(BigDecimal amount);

    // ------------------------------------------------------------------ access

    /**
     * @return the dkBank API
     * @throws IllegalStateException if dkBank isn't installed or enabled yet
     */
    static DkBankAPI get() {
        RegisteredServiceProvider<DkBankAPI> provider = Bukkit.getServicesManager().getRegistration(DkBankAPI.class);
        if (provider == null) {
            throw new IllegalStateException("dkBank isn't enabled. Add dkBank to depend or softdepend in your plugin.yml.");
        }
        return provider.getProvider();
    }

    /**
     * @return true if dkBank is installed and enabled
     */
    static boolean isAvailable() {
        return Bukkit.getServicesManager().isProvidedFor(DkBankAPI.class);
    }
}
