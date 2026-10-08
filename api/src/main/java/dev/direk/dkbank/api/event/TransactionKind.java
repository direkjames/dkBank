package dev.direk.dkbank.api.event;

/** Every kind of change to a bank balance. */
public enum TransactionKind {
    /** Wallet to bank. */
    DEPOSIT,
    /** Bank to wallet. */
    WITHDRAW,
    /** Sent to another player's bank (the sender's side). */
    TRANSFER_OUT,
    /** Received from another player's bank (the receiver's side). */
    TRANSFER_IN,
    /** Added by staff. */
    ADMIN_GIVE,
    /** Removed by staff. */
    ADMIN_TAKE,
    /** Set by staff. */
    ADMIN_SET,
    /** Put back after a step failed. */
    REFUND,
    /** Interest paid. */
    INTEREST,
    /** A tier bought. */
    UPGRADE,
    /** Added by another plugin through the API. */
    PLUGIN_GIVE,
    /** Taken by another plugin through the API. */
    PLUGIN_TAKE
}
