package dev.direk.dkbank.storage;

import dev.direk.dkbank.interest.InterestPlan;
import dev.direk.dkbank.money.AmountInput;
import dev.direk.dkbank.storage.StoreTypes.Activity;
import dev.direk.dkbank.storage.StoreTypes.Beat;
import dev.direk.dkbank.storage.StoreTypes.TopEntry;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Leaderboard, totals and the economy report. */
class ReportStoreTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final long HOUR = 3_600_000L;
    private static final long DAY = 24 * HOUR;

    private final AtomicLong clock = new AtomicLong(10 * DAY);

    private static BigDecimal $(String value) {
        return new BigDecimal(value);
    }

    private BankStore store() throws Exception {
        Path dir = Files.createTempDirectory("dkbank-report");
        SQLiteConfig config = new SQLiteConfig();
        config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        SQLiteDataSource ds = new SQLiteDataSource(config);
        ds.setUrl("jdbc:sqlite:" + dir.resolve("bank.db"));
        BankStore store = new BankStore(ds, SqlDialect.SQLITE, "dkbank_", clock::get);
        store.createSchema();
        store.ensureAccount(A, "Alice");
        store.ensureAccount(B, "Bob");
        store.ensureAccount(C, "Cara");
        return store;
    }

    @Test
    void leaderboardAndTotals() throws Exception {
        BankStore store = store();
        store.credit(A, $("500"), TransactionType.DEPOSIT, null, null);
        store.credit(B, $("900.50"), TransactionType.DEPOSIT, null, null);
        List<TopEntry> top = store.top(10);
        assertEquals(List.of("Bob", "Alice"), top.stream().map(TopEntry::name).toList(), "empty accounts aren't listed");
        assertEquals($("900.50"), top.getFirst().balance());
        assertEquals(1, store.top(1).size());

        StoreTypes.Totals totals = store.totals();
        assertEquals(3L, totals.accounts());
        assertEquals($("1400.50"), totals.balance());
    }

    @Test
    void activityCountsOnlyTheTimeSpan() throws Exception {
        BankStore store = store();
        store.credit(A, $("100"), TransactionType.DEPOSIT, null, null);          // old
        clock.addAndGet(5 * DAY);
        long since = clock.get() - DAY;
        store.credit(A, $("200"), TransactionType.DEPOSIT, null, null);
        store.credit(B, $("300"), TransactionType.DEPOSIT, null, null);
        store.debit(A, AmountInput.parse("100"), $("10"), TransactionType.WITHDRAW, null);

        Map<TransactionType, Activity> byType = store.activity(since).stream()
                .collect(Collectors.toMap(Activity::type, a -> a));
        assertEquals(2L, byType.get(TransactionType.DEPOSIT).count());
        assertEquals($("500.00"), byType.get(TransactionType.DEPOSIT).amount());
        assertEquals($("10.00"), byType.get(TransactionType.WITHDRAW).fees());
    }

    @Test
    void topInterestEarners() throws Exception {
        BankStore store = store();
        store.credit(A, $("5"), TransactionType.INTEREST, null, "online");
        store.credit(A, $("5"), TransactionType.INTEREST, null, "offline");
        store.credit(B, $("7"), TransactionType.INTEREST, null, "online");
        store.credit(C, $("50"), TransactionType.DEPOSIT, null, null);
        var earners = store.topBy(TransactionType.INTEREST, 0, 5);
        assertEquals("Alice", earners.getFirst().name());
        assertEquals($("10.00"), earners.getFirst().amount());
        assertEquals(2, earners.size());
    }

    @Test
    void beatsReportCycleProgress() throws Exception {
        BankStore store = store();
        store.credit(A, $("1000"), TransactionType.DEPOSIT, null, null);
        InterestPlan plan = new InterestPlan(true, BigDecimal.ONE, HOUR, true, BigDecimal.ONE, DAY, 7 * DAY, null, null);
        store.settleLogin(A, plan, null);
        assertEquals(20 * 60_000L, store.beat(new Beat(A, 20 * 60_000L, clock.addAndGet(20 * 60_000L), true, plan, null)).cycleMillis());
        assertEquals(5 * 60_000L, store.beat(new Beat(A, 45 * 60_000L, clock.addAndGet(45 * 60_000L), true, plan, null)).cycleMillis(),
                "after a payout, the time past the hour is the start of the next");
    }
}
