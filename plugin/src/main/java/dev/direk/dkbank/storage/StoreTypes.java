package dev.direk.dkbank.storage;

import dev.direk.dkbank.interest.InterestPlan;
import dev.direk.dkbank.money.Money;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Values returned by {@link BankStore}. */
public final class StoreTypes {

    private StoreTypes() {
    }

    /** @param tier the bought tier's id, or null for the first tier */
    public record Account(UUID uuid, String name, BigDecimal balance, @Nullable String tier) {
    }

    /** One line of an account's history. */
    public record Entry(long id, TransactionType type, BigDecimal amount, BigDecimal fee, BigDecimal balanceAfter,
                        @Nullable UUID otherUuid, @Nullable String otherName, @Nullable String actor, long time) {
    }

    public record Page(List<Entry> entries, int page, int pages, long total) {
    }

    /** Why a money operation didn't happen. */
    public enum Failure {
        /** The account doesn't exist (the player has never joined). */
        NO_ACCOUNT,
        /** Not enough money in the bank. */
        INSUFFICIENT_FUNDS,
        /** The receiving account would go over its maximum balance. */
        BALANCE_LIMIT,
        /** The amount works out to nothing (e.g. "half" of 0.01). */
        NOTHING_TO_MOVE,
        /** The amount (after resolving "all"/"half") is below the minimum. */
        BELOW_MINIMUM,
        /** The account's tier changed since the upgrade was offered (e.g. bought on another server). */
        TIER_CHANGED
    }

    /** The maximum balance of a transfer's receiver, worked out from their account; null for no limit. */
    @FunctionalInterface
    public interface ReceiverLimit {
        @Nullable BigDecimal maxBalance(Account receiver);
    }

    /**
     * Per-operation amount limits, checked inside the transaction so they also apply to "all" and "half".
     *
     * @param min smallest amount allowed (zero for none)
     * @param cap largest amount per operation; "all"/"half" are reduced to it, fixed amounts above it fail.
     *            Null for none.
     */
    public record Limits(BigDecimal min, @Nullable BigDecimal cap) {
        public static final Limits NONE = new Limits(BigDecimal.ZERO, null);

        public Limits {
            min = Money.floor(min);
            cap = cap == null ? null : Money.floor(cap);
        }
    }

    /**
     * Result of a money operation: either success with the amounts actually moved, or a failure reason.
     *
     * @param amount     the amount moved (after resolving "all"/"half")
     * @param fee        the fee charged
     * @param balance    the account's balance afterwards
     * @param available  for {@link Failure#INSUFFICIENT_FUNDS}: the balance that was available
     */
    public record Result(@Nullable Failure failure, BigDecimal amount, BigDecimal fee, BigDecimal balance,
                         BigDecimal available) {

        static Result ok(BigDecimal amount, BigDecimal fee, BigDecimal balance) {
            return new Result(null, amount, fee, balance, balance);
        }

        static Result fail(Failure failure, BigDecimal available) {
            return new Result(failure, BigDecimal.ZERO, BigDecimal.ZERO, available, available);
        }

        public boolean ok() {
            return failure == null;
        }
    }

    /**
     * An account's interest progress.
     *
     * @param base          lowest balance since the last payout
     * @param activeMillis  active time in the current payout cycle
     * @param afkMillis     AFK time in the current payout cycle
     * @param lastSeen      when the player was last online (0 = never recorded)
     */
    public record InterestState(BigDecimal balance, BigDecimal base, long activeMillis, long afkMillis, long lastSeen) {
    }

    /**
     * One minute (or so) of a player being online, for the interest cycle.
     *
     * @param elapsedMillis online time since this server last recorded the player
     * @param at            when it was measured (the end of that time)
     */
    public record Beat(UUID uuid, long elapsedMillis, long at, boolean active, InterestPlan plan,
                       @Nullable BigDecimal maxBalance) {
    }

    /**
     * Interest paid.
     *
     * @param amount        interest added (zero if none)
     * @param offlineMillis offline time it covers (login payouts only)
     * @param balance       balance afterwards
     * @param cycleMillis   online time now in the payout cycle (towards the next payout)
     */
    public record Payout(BigDecimal amount, long offlineMillis, BigDecimal balance, long cycleMillis) {
        static Payout none(BigDecimal balance) {
            return new Payout(Money.ZERO, 0, balance, 0);
        }

        public boolean paid() {
            return amount.signum() > 0;
        }
    }

    /**
     * An account seen on an IP address, for the alt-account limit.
     *
     * @param firstSeen when it first logged in from that address
     * @param exempt    staff allowed it, whatever the limit
     */
    public record AltAccount(UUID uuid, String name, long firstSeen, long lastSeen, boolean exempt) {
    }

    /** One line of the leaderboard. */
    public record TopEntry(UUID uuid, String name, BigDecimal balance) {
    }

    /** Every account together. */
    public record Totals(long accounts, BigDecimal balance) {
    }

    /**
     * What happened with one kind of transaction in a time span.
     *
     * @param amount sum of the amounts
     * @param fees   sum of the fees
     */
    public record Activity(TransactionType type, long count, BigDecimal amount, BigDecimal fees) {
    }

    /** An account and a sum, e.g. interest earned in a week. */
    public record Earner(String name, BigDecimal amount) {
    }

    /** Result of a transfer: the sender's side, plus the receiver's new balance. */
    public record TransferResult(Result sender, BigDecimal receiverBalance, String receiverName) {
    }
}
