package dev.direk.dkbank.bank;

import dev.direk.dkbank.storage.StoreTypes.AltAccount;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AltRuleTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID D = UUID.fromString("00000000-0000-0000-0000-00000000000d");

    private static AltAccount acc(UUID uuid, boolean exempt) {
        return new AltAccount(uuid, uuid.toString().substring(35), 0, 0, exempt);
    }

    @Test
    void firstAccountsAreAllowedLaterOnesLocked() {
        List<AltAccount> onIp = List.of(acc(A, false), acc(B, false), acc(C, false));
        assertFalse(AltRule.overLimit(onIp, A, 2));
        assertFalse(AltRule.overLimit(onIp, B, 2));
        assertTrue(AltRule.overLimit(onIp, C, 2));
    }

    @Test
    void allowedAccountsDontCount() {
        List<AltAccount> onIp = List.of(acc(A, false), acc(B, true), acc(C, false), acc(D, false));
        assertFalse(AltRule.overLimit(onIp, B, 1), "allowed is never locked");
        assertFalse(AltRule.overLimit(onIp, A, 2));
        assertFalse(AltRule.overLimit(onIp, C, 2), "B doesn't take a place");
        assertTrue(AltRule.overLimit(onIp, D, 2));
    }

    @Test
    void zeroMeansNoLimit() {
        assertFalse(AltRule.overLimit(List.of(acc(A, false), acc(B, false)), B, 0));
    }

    @Test
    void unknownAccountIsntLocked() {
        assertFalse(AltRule.overLimit(List.of(acc(A, false)), D, 1));
    }
}
