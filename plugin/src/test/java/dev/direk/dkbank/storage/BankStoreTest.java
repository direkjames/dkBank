package dev.direk.dkbank.storage;

import dev.direk.dkbank.money.AmountInput;
import dev.direk.dkbank.storage.StoreTypes.Failure;
import dev.direk.dkbank.storage.StoreTypes.Page;
import dev.direk.dkbank.storage.StoreTypes.Result;
import dev.direk.dkbank.storage.StoreTypes.TransferResult;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs against a real SQLite database file, configured the way dkBank configures it on a server.
 */
class BankStoreTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final BigDecimal NO_FEE = BigDecimal.ZERO;

    private final AtomicLong clock = new AtomicLong(1_000_000);

    private static BigDecimal $(String value) {
        return new BigDecimal(value);
    }

    private static SQLiteDataSource dataSource() throws Exception {
        Path dir = Files.createTempDirectory("dkbank-test");
        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.FULL);
        config.setBusyTimeout(10_000);
        config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        SQLiteDataSource ds = new SQLiteDataSource(config);
        ds.setUrl("jdbc:sqlite:" + dir.resolve("bank.db"));
        return ds;
    }

    /** A fresh store with Alice and Bob, each holding {@code start}. */
    private BankStore store(SQLiteDataSource ds, String start) {
        BankStore store = new BankStore(ds, SqlDialect.SQLITE, "dkbank_", clock::incrementAndGet);
        store.createSchema();
        store.ensureAccount(ALICE, "Alice");
        store.ensureAccount(BOB, "Bob");
        if ($(start).signum() > 0) {
            store.credit(ALICE, $(start), TransactionType.DEPOSIT, null, null);
            store.credit(BOB, $(start), TransactionType.DEPOSIT, null, null);
        }
        return store;
    }

    private BankStore store(String start) throws Exception {
        return store(dataSource(), start);
    }

    private static BigDecimal balance(BankStore store, UUID uuid) {
        return store.account(uuid).orElseThrow().balance();
    }

    // ------------------------------------------------------------------ accounts

    @Test
    void schemaCanBeCreatedTwice() throws Exception {
        SQLiteDataSource ds = dataSource();
        BankStore store = store(ds, "0");
        store.createSchema();
        assertEquals($("0.00"), balance(store, ALICE));
    }

    @Test
    void accountsAreCreatedOnceAndKeepTheirName() throws Exception {
        BankStore store = store("50");
        assertEquals($("50.00"), store.ensureAccount(ALICE, "Alice").balance()); // no reset on rejoin
        store.ensureAccount(ALICE, "AliceRenamed");
        assertEquals("AliceRenamed", store.account(ALICE).orElseThrow().name());
        assertEquals(ALICE, store.accountByName("alicerenamed").orElseThrow().uuid()); // ignores case
        assertTrue(store.accountByName("Nobody").isEmpty());
    }

    // ------------------------------------------------------------------ in and out

    @Test
    void creditAndDebit() throws Exception {
        BankStore store = store("0");
        Result in = store.credit(ALICE, $("1234.56"), TransactionType.DEPOSIT, null, null);
        assertTrue(in.ok());
        assertEquals($("1234.56"), in.balance());

        Result out = store.debit(ALICE, AmountInput.parse("234.56"), NO_FEE, TransactionType.WITHDRAW, null);
        assertTrue(out.ok());
        assertEquals($("1000.00"), out.balance());
        assertEquals($("1000.00"), balance(store, ALICE));
    }

    @Test
    void cannotTakeMoreThanTheBalance() throws Exception {
        BankStore store = store("100");
        Result r = store.debit(ALICE, AmountInput.parse("100.01"), NO_FEE, TransactionType.WITHDRAW, null);
        assertEquals(Failure.INSUFFICIENT_FUNDS, r.failure());
        assertEquals($("100.00"), r.available());
        assertEquals($("100.00"), balance(store, ALICE));
    }

    @Test
    void allAndHalfUseTheBalanceAtThatMoment() throws Exception {
        BankStore store = store("100.01");
        assertEquals($("50.00"), store.debit(ALICE, AmountInput.HALF, NO_FEE, TransactionType.WITHDRAW, null).amount());
        assertEquals($("50.01"), store.debit(ALICE, AmountInput.ALL, NO_FEE, TransactionType.WITHDRAW, null).amount());
        assertEquals(Failure.INSUFFICIENT_FUNDS, store.debit(ALICE, AmountInput.ALL, NO_FEE, TransactionType.WITHDRAW, null).failure());
    }

    @Test
    void limitsApplyToSharesToo() throws Exception {
        BankStore store = store("1000");
        StoreTypes.Limits limits = new StoreTypes.Limits($("5"), $("300"));
        assertEquals($("300.00"), store.debit(ALICE, AmountInput.ALL, NO_FEE, TransactionType.WITHDRAW, null, limits).amount());
        assertEquals(Failure.BELOW_MINIMUM, store.debit(ALICE, AmountInput.parse("4.99"), NO_FEE, TransactionType.WITHDRAW, null, limits).failure());
        assertEquals($("300.00"), store.transfer(ALICE, BOB, AmountInput.ALL, NO_FEE, null, limits).sender().amount());
        assertEquals(Failure.BELOW_MINIMUM, store.transfer(ALICE, BOB, AmountInput.parse("1"), NO_FEE, null, limits).sender().failure());
        assertEquals($("400.00"), balance(store, ALICE));
    }

    @Test
    void withdrawFeeComesOutOfTheAmount() throws Exception {
        BankStore store = store("1000");
        Result r = store.debit(ALICE, AmountInput.parse("200"), $("2.5"), TransactionType.WITHDRAW, null);
        assertEquals($("200.00"), r.amount());
        assertEquals($("5.00"), r.fee());      // the player receives 195
        assertEquals($("800.00"), r.balance());
    }

    @Test
    void creditRespectsTheMaximumBalance() throws Exception {
        BankStore store = store("900");
        assertEquals(Failure.BALANCE_LIMIT, store.credit(ALICE, $("100.01"), TransactionType.DEPOSIT, $("1000"), null).failure());
        assertTrue(store.credit(ALICE, $("100"), TransactionType.DEPOSIT, $("1000"), null).ok());
    }

    @Test
    void unknownAccountsFailCleanly() throws Exception {
        BankStore store = store("0");
        UUID stranger = UUID.randomUUID();
        assertEquals(Failure.NO_ACCOUNT, store.credit(stranger, $("1"), TransactionType.ADMIN_GIVE, null, "admin").failure());
        assertEquals(Failure.NO_ACCOUNT, store.transfer(ALICE, stranger, AmountInput.parse("1"), NO_FEE, null).sender().failure());
    }

    @Test
    void adminSet() throws Exception {
        BankStore store = store("10");
        assertEquals($("777.77"), store.set(ALICE, $("777.77"), "Console").balance());
        assertEquals($("777.77"), balance(store, ALICE));
    }

    // ------------------------------------------------------------------ transfers

    @Test
    void transferMovesMoneyAndChargesTheSenderAFee() throws Exception {
        BankStore store = store("1000");
        TransferResult t = store.transfer(ALICE, BOB, AmountInput.parse("100"), $("2"), null);
        assertTrue(t.sender().ok());
        assertEquals($("100.00"), t.sender().amount());
        assertEquals($("2.00"), t.sender().fee());
        assertEquals($("898.00"), balance(store, ALICE));
        assertEquals($("1100.00"), balance(store, BOB));
        assertEquals("Bob", t.receiverName());
    }

    @Test
    void transferAllLeavesRoomForTheFee() throws Exception {
        BankStore store = store("1000");
        TransferResult t = store.transfer(ALICE, BOB, AmountInput.ALL, $("2.5"), null);
        assertTrue(t.sender().ok());
        assertTrue(t.sender().balance().signum() >= 0);
        assertTrue(t.sender().balance().compareTo($("0.03")) < 0, "almost everything should be sent");
    }

    @Test
    void failedTransferChangesNothing() throws Exception {
        BankStore store = store("100");
        assertEquals(Failure.INSUFFICIENT_FUNDS, store.transfer(ALICE, BOB, AmountInput.parse("99"), $("5"), null).sender().failure());
        assertEquals(Failure.BALANCE_LIMIT, store.transfer(ALICE, BOB, AmountInput.parse("50"), NO_FEE, $("120")).sender().failure());
        assertEquals($("100.00"), balance(store, ALICE));
        assertEquals($("100.00"), balance(store, BOB));
    }

    // ------------------------------------------------------------------ history

    @Test
    void historyIsNewestFirstAndPaged() throws Exception {
        BankStore store = store("0");
        for (int i = 1; i <= 12; i++) store.credit(ALICE, $(String.valueOf(i)), TransactionType.DEPOSIT, null, null);
        store.transfer(ALICE, BOB, AmountInput.parse("5"), NO_FEE, null);

        Page first = store.history(ALICE, 1, 5);
        assertEquals(13, first.total());
        assertEquals(3, first.pages());
        assertEquals(TransactionType.TRANSFER_OUT, first.entries().get(0).type());
        assertEquals(BOB, first.entries().get(0).otherUuid());
        assertEquals($("12.00"), first.entries().get(1).amount());

        Page last = store.history(ALICE, 99, 5); // clamped to the last page
        assertEquals(3, last.page());
        assertEquals(3, last.entries().size());

        Page bob = store.history(BOB, 1, 5);
        assertEquals(TransactionType.TRANSFER_IN, bob.entries().get(0).type());
        assertEquals("Alice", bob.entries().get(0).otherName());
    }

    // ------------------------------------------------------------------ safety

    @Test
    void aFailureHalfwayLeavesTheBalanceUntouched() throws Exception {
        SQLiteDataSource ds = dataSource();
        BankStore store = store(ds, "100");
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE dkbank_transactions"); // the history write will now fail
        }
        assertThrows(BankStore.StorageException.class,
                () -> store.credit(ALICE, $("50"), TransactionType.DEPOSIT, null, null));
        assertEquals($("100.00"), balance(store, ALICE), "the balance change must be rolled back");
    }

    @Test
    void parallelWithdrawalsNeverOverdraw() throws Exception {
        BankStore store = store("1000");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Result>> results = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            results.add(pool.submit(() -> store.debit(ALICE, AmountInput.parse("7"), NO_FEE, TransactionType.WITHDRAW, null)));
        }
        int ok = 0;
        for (Future<Result> f : results) if (f.get().ok()) ok++;
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        BigDecimal left = balance(store, ALICE);
        assertEquals(142, ok, "1000 / 7 = 142 withdrawals fit");
        assertEquals($("6.00"), left);
        assertFalse(left.signum() < 0);
    }

    @Test
    void parallelOppositeTransfersKeepTheTotal() throws Exception {
        BankStore store = store("500");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<TransferResult>> results = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            boolean even = i % 2 == 0;
            results.add(pool.submit(() -> store.transfer(even ? ALICE : BOB, even ? BOB : ALICE,
                    AmountInput.parse("13.37"), NO_FEE, null)));
        }
        for (Future<TransferResult> f : results) f.get(); // no exceptions, no deadlocks
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals($("1000.00"), balance(store, ALICE).add(balance(store, BOB)), "money must not be created or lost");
    }
}
