package dev.direk.dkbank.api;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;

/**
 * What happened to a money operation.
 *
 * @param failure null if it worked
 * @param amount  the amount moved (zero if it failed)
 * @param balance the account's balance afterwards (or the balance it had, if it failed)
 */
public record BankResult(@Nullable Failure failure, BigDecimal amount, BigDecimal balance) {

    /** Why an operation didn't happen. Nothing was changed. */
    public enum Failure {
        /** The player has never joined, so there's no account. */
        NO_ACCOUNT,
        /** Not enough money in the bank. */
        INSUFFICIENT_FUNDS,
        /** The account would go over its maximum balance. */
        BALANCE_LIMIT,
        /** The amount was zero, negative or too large. */
        INVALID_AMOUNT,
        /** Sending money to the same account. */
        SAME_ACCOUNT,
        /** The database failed (the error is in the console). */
        ERROR
    }

    /** @return true if the money moved */
    public boolean success() {
        return failure == null;
    }
}
