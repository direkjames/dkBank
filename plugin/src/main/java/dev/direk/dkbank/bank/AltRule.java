package dev.direk.dkbank.bank;

import dev.direk.dkbank.storage.StoreTypes.AltAccount;

import java.util.List;
import java.util.UUID;

/** The alt-account rule on its own, so it can be tested without a server. */
public final class AltRule {

    private AltRule() {
    }

    /**
     * Whether {@code uuid} is over the limit, given every account seen on its address in the order they
     * first used it. Only the first {@code max} accounts may use the bank. Allowed (exempt) accounts don't
     * count towards the limit and are never locked. {@code max} of 0 or less means no limit.
     */
    public static boolean overLimit(List<AltAccount> onAddress, UUID uuid, int max) {
        if (max <= 0) return false;
        int counted = 0;
        for (AltAccount account : onAddress) {
            if (account.exempt()) {
                if (account.uuid().equals(uuid)) return false;
                continue;
            }
            if (account.uuid().equals(uuid)) return counted >= max;
            counted++;
        }
        return false;
    }
}
