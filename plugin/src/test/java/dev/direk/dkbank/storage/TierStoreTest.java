package dev.direk.dkbank.storage;

import dev.direk.dkbank.money.AmountInput;
import dev.direk.dkbank.storage.StoreTypes.Entry;
import dev.direk.dkbank.storage.StoreTypes.Failure;
import dev.direk.dkbank.storage.StoreTypes.Limits;
import dev.direk.dkbank.storage.StoreTypes.Result;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bank tiers in the database: buying, admin changes, upgrading old tables. */
class TierStoreTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private final AtomicLong clock = new AtomicLong(1_000_000);

    private static BigDecimal $(String value) {
        return new BigDecimal(value);
    }

    private static SQLiteDataSource dataSource() throws Exception {
        Path dir = Files.createTempDirectory("dkbank-tiers");
        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setBusyTimeout(10_000);
        config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        SQLiteDataSource ds = new SQLiteDataSource(config);
        ds.setUrl("jdbc:sqlite:" + dir.resolve("bank.db"));
        return ds;
    }

    private BankStore store(String aliceBalance) throws Exception {
        BankStore store = new BankStore(dataSource(), SqlDialect.SQLITE, "dkbank_", clock::incrementAndGet);
        store.createSchema();
        store.ensureAccount(ALICE, "Alice");
        store.ensureAccount(BOB, "Bob");
        store.credit(ALICE, $(aliceBalance), TransactionType.DEPOSIT, null, null);
        return store;
    }

    private static String tier(BankStore store, UUID uuid) {
        return store.account(uuid).orElseThrow().tier();
    }

    @Test
    void newAccountsStartOnTheFirstTier() throws Exception {
        assertEquals(null, tier(store("0"), ALICE));
    }

    @Test
    void upgradeTakesTheCostAndStoresTheTier() throws Exception {
        BankStore store = store("30000");
        Result r = store.upgrade(ALICE, null, "silver", $("25000"));
        assertTrue(r.ok());
        assertEquals($("5000.00"), r.balance());
        assertEquals("silver", tier(store, ALICE));

        Entry e = store.history(ALICE, 1, 10).entries().getFirst();
        assertEquals(TransactionType.UPGRADE, e.type());
        assertEquals($("25000.00"), e.amount());
        assertEquals("silver", e.otherName());
    }

    @Test
    void upgradeNeedsTheMoney() throws Exception {
        BankStore store = store("24999.99");
        assertEquals(Failure.INSUFFICIENT_FUNDS, store.upgrade(ALICE, null, "silver", $("25000")).failure());
        assertEquals(null, tier(store, ALICE));
        assertEquals($("24999.99"), store.account(ALICE).orElseThrow().balance());
    }

    @Test
    void upgradeFailsIfTheTierChangedMeanwhile() throws Exception {
        BankStore store = store("100000");
        store.setTier(ALICE, "gold", "Admin");
        assertEquals(Failure.TIER_CHANGED, store.upgrade(ALICE, null, "silver", $("25000")).failure());
        assertEquals("gold", tier(store, ALICE));
        assertEquals($("100000.00"), store.account(ALICE).orElseThrow().balance());
    }

    /** Two clicks (or two servers) buying the same upgrade at once: only one is charged. */
    @Test
    void buyingTwiceAtOnceChargesOnce() throws Exception {
        BankStore store = store("100000");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Result>> results = new ArrayList<>();
        for (int i = 0; i < 8; i++) results.add(pool.submit(() -> store.upgrade(ALICE, null, "silver", $("25000"))));
        int ok = 0;
        for (Future<Result> f : results) if (f.get().ok()) ok++;
        pool.shutdown();
        assertEquals(1, ok);
        assertEquals($("75000.00"), store.account(ALICE).orElseThrow().balance());
    }

    @Test
    void adminSetsAndResetsTheTier() throws Exception {
        BankStore store = store("10");
        assertTrue(store.setTier(ALICE, "platinum", "Admin").ok());
        assertEquals("platinum", tier(store, ALICE));
        Entry e = store.history(ALICE, 1, 10).entries().getFirst();
        assertEquals(TransactionType.TIER_SET, e.type());
        assertEquals("Admin", e.actor());
        assertEquals($("10.00"), e.balanceAfter());

        assertTrue(store.setTier(ALICE, null, "Admin").ok());
        assertEquals(null, tier(store, ALICE));
        assertEquals(Failure.NO_ACCOUNT, store.setTier(UUID.randomUUID(), "gold", "Admin").failure());
    }

    @Test
    void receiverLimitComesFromTheReceiversTier() throws Exception {
        BankStore store = store("1000");
        store.setTier(BOB, "small", null);
        Result r = store.transfer(ALICE, BOB, AmountInput.parse("200"), BigDecimal.ZERO,
                receiver -> "small".equals(receiver.tier()) ? $("100") : null, Limits.NONE).sender();
        assertEquals(Failure.BALANCE_LIMIT, r.failure());
        r = store.transfer(ALICE, BOB, AmountInput.parse("100"), BigDecimal.ZERO,
                receiver -> "small".equals(receiver.tier()) ? $("100") : null, Limits.NONE).sender();
        assertTrue(r.ok());
    }

    @Test
    void accountByNameIncludesTheTier() throws Exception {
        BankStore store = store("0");
        store.setTier(BOB, "gold", null);
        assertEquals("gold", store.accountByName("bob").orElseThrow().tier());
    }

    @Test
    void upgradesVersionTwoTables() throws Exception {
        SQLiteDataSource ds = dataSource();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            for (String sql : SqlDialect.SQLITE.schema("dkbank_")) st.execute(sql);
            st.execute("INSERT INTO dkbank_meta VALUES ('schema_version', '1')");
            st.execute("INSERT INTO dkbank_accounts (uuid, name, balance, created_at, updated_at) VALUES ('"
                    + ALICE + "', 'Alice', 50000, 1, 1)");
        }
        BankStore store = new BankStore(ds, SqlDialect.SQLITE, "dkbank_", clock::incrementAndGet);
        store.createSchema(); // 1 → 2 → 3
        assertEquals(null, tier(store, ALICE));
        assertEquals($("500.00"), store.account(ALICE).orElseThrow().balance());
        assertTrue(store.upgrade(ALICE, null, "silver", $("100")).ok());
    }
}
