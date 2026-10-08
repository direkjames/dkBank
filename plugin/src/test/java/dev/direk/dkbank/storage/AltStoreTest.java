package dev.direk.dkbank.storage;

import dev.direk.dkbank.storage.StoreTypes.AltAccount;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Login addresses for the alt-account limit, against a real SQLite database. */
class AltStoreTest {

    private static final UUID MAIN = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID ALT = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final long DAY = 86_400_000L;

    private final AtomicLong clock = new AtomicLong(100 * DAY);

    private BankStore store() throws Exception {
        Path dir = Files.createTempDirectory("dkbank-alts");
        SQLiteConfig config = new SQLiteConfig();
        config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        SQLiteDataSource ds = new SQLiteDataSource(config);
        ds.setUrl("jdbc:sqlite:" + dir.resolve("bank.db"));
        BankStore store = new BankStore(ds, SqlDialect.SQLITE, "dkbank_", clock::get);
        store.createSchema();
        store.ensureAccount(MAIN, "Main");
        store.ensureAccount(ALT, "Alt");
        store.ensureAccount(OTHER, "Other");
        return store;
    }

    private static List<UUID> uuids(List<AltAccount> accounts) {
        return accounts.stream().map(AltAccount::uuid).toList();
    }

    @Test
    void listsAccountsInTheOrderTheyFirstUsedTheAddress() throws Exception {
        BankStore store = store();
        store.recordLogin(MAIN, "home", 0);
        clock.addAndGet(1000);
        store.recordLogin(OTHER, "elsewhere", 0);
        clock.addAndGet(1000);
        List<AltAccount> home = store.recordLogin(ALT, "home", 0);
        assertEquals(List.of(MAIN, ALT), uuids(home));
        assertEquals("Alt", home.get(1).name());

        clock.addAndGet(1000);
        store.recordLogin(MAIN, "home", 0); // logging in again keeps the first-seen order
        assertEquals(List.of(MAIN, ALT), uuids(store.accountsOnIp("home", 0)));
    }

    @Test
    void oldLoginsDontCount() throws Exception {
        BankStore store = store();
        store.recordLogin(MAIN, "home", 0);
        clock.addAndGet(40 * DAY);
        List<AltAccount> home = store.recordLogin(ALT, "home", clock.get() - 30 * DAY);
        assertEquals(List.of(ALT), uuids(home), "the main account hasn't used it in 40 days");
    }

    @Test
    void exemptionsAndLatestAddress() throws Exception {
        BankStore store = store();
        store.recordLogin(ALT, "home", 0);
        clock.addAndGet(1000);
        store.recordLogin(ALT, "school", 0);
        assertEquals("school", store.latestIp(ALT).orElseThrow());
        assertFalse(store.latestIp(OTHER).isPresent());

        assertTrue(store.setAltExempt(ALT, true));
        assertTrue(store.accountsOnIp("home", 0).getFirst().exempt());
        assertFalse(store.setAltExempt(UUID.randomUUID(), true));
    }

    @Test
    void saltIsMadeOnceAndKept() throws Exception {
        BankStore store = store();
        String salt = store.ipSalt();
        assertEquals(64, salt.length());
        assertEquals(salt, store.ipSalt());
    }

    @Test
    void pruneForgetsOldLogins() throws Exception {
        BankStore store = store();
        store.recordLogin(MAIN, "home", 0);
        clock.addAndGet(100 * DAY);
        store.recordLogin(ALT, "home", 0);
        assertEquals(1, store.pruneIps(clock.get() - 90 * DAY));
        assertEquals(List.of(ALT), uuids(store.accountsOnIp("home", 0)));
    }
}
