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

    /** One minute (or so) of a player being online, for the interest cycle. */
    public record Beat(UUID uuid, long elapsedMillis, boolean active, InterestPlan plan, @Nullable BigDecimal maxBalance) {
    }

    /**
     * Interest paid.
     *
     * @param amount        interest added (zero if none)
     * @param offlineMillis offline time it covers (login payouts only)
     * @param balance       balance afterwards
     */
    public record Payout(BigDecimal amount, long offlineMillis, BigDecimal balance) {
        static Payout none(BigDecimal balance) {
            return new Payout(Money.ZERO, 0, balance);
        }

        public boolean paid() {
            return amount.signum() > 0;
        }
    }

    /** Result of a transfer: the sender's side, plus the receiver's new balance. */
    public record TransferResult(Result sender, BigDecimal receiverBalance, String receiverName) {
    }
}
