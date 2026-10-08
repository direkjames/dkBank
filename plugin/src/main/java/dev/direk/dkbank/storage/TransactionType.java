package dev.direk.dkbank.storage;

/** What a line in an account's history is. Stored by name, so never rename these. */
public enum TransactionType {
    /** Wallet to bank. */
    DEPOSIT,
    /** Bank to wallet. */
    WITHDRAW,
    /** Sent to another player's bank. */
    TRANSFER_OUT,
    /** Received from another player's bank. */
    TRANSFER_IN,
    /** Added by an admin. */
    ADMIN_GIVE,
    /** Removed by an admin. */
    ADMIN_TAKE,
    /** Set by an admin. */
    ADMIN_SET,
    /** Money put back after a step failed (e.g. the wallet refused a withdrawal). */
    REFUND,
    /** Interest paid (from version 0.2). */
    INTEREST,
    /** Bank tier bought; the tier is stored as the other name (from version 0.3). */
    UPGRADE,
    /** Bank tier changed by an admin; amount is 0 (from version 0.3). */
    TIER_SET,
    /** Added by another plugin through the API (from version 0.6). */
    PLUGIN_GIVE,
    /** Taken by another plugin through the API (from version 0.6). */
    PLUGIN_TAKE
}
