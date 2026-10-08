package dev.direk.dkbank.storage;

import dev.direk.dkbank.interest.InterestPlan;
import dev.direk.dkbank.money.AmountInput;
import dev.direk.dkbank.storage.StoreTypes.Beat;
import dev.direk.dkbank.storage.StoreTypes.InterestState;
import dev.direk.dkbank.storage.StoreTypes.Payout;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Interest payouts against a real SQLite database. 1% per hour online, 1% per day offline. */
class InterestStoreTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;
    private static final BigDecimal ONE = BigDecimal.ONE;

    private final AtomicLong clock = new AtomicLong(10 * DAY);

    private static BigDecimal $(String value) {
        return new BigDecimal(value);
    }

    private static InterestPlan plan(String cap, String maxPerPayout) {
        return new InterestPlan(true, ONE, HOUR, true, ONE, DAY, 7 * DAY,
                cap == null ? null : $(cap), maxPerPayout == null ? null : $(maxPerPayout));
    }

    private static final InterestPlan PLAN = plan(null, null);

    private static SQLiteDataSource dataSource() throws Exception {
        Path dir = Files.createTempDirectory("dkbank-interest");
        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        SQLiteDataSource ds = new SQLiteDataSource(config);
        ds.setUrl("jdbc:sqlite:" + dir.resolve("bank.db"));
        return ds;
    }

    /** Alice with {@code start} in the bank, logged in (so her interest base is her balance). */
    private BankStore aliceWith(String start) throws Exception {
        BankStore store = new BankStore(dataSource(), SqlDialect.SQLITE, "dkbank_", clock::get);
        store.createSchema();
        store.ensureAccount(ALICE, "Alice");
        store.credit(ALICE, $(start), TransactionType.DEPOSIT, null, null);
        store.settleLogin(ALICE, PLAN, null); // first login ever: nothing paid, base = balance
        return store;
    }

    private Beat beat(long millis, boolean active, InterestPlan plan, String maxBalance) {
        clock.addAndGet(millis);
        return new Beat(ALICE, millis, clock.get(), active, plan, maxBalance == null ? null : $(maxBalance));
    }

    private Beat beat(long millis, boolean active) {
        return beat(millis, active, PLAN, null);
    }

    private static InterestState state(BankStore store) {
        return store.interestState(ALICE).orElseThrow();
    }

    // ------------------------------------------------------------------ schema

    @Test
    void upgradesVersionOneTables() throws Exception {
        SQLiteDataSource ds = dataSource();
        // Tables exactly as 0.1.0 created them, with one account holding 500.00.
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            for (String sql : SqlDialect.SQLITE.schema("dkbank_")) st.execute(sql);
            st.execute("INSERT INTO dkbank_meta VALUES ('schema_version', '1')");
            st.execute("INSERT INTO dkbank_accounts (uuid, name, balance, created_at, updated_at) VALUES ('"
                    + ALICE + "', 'Alice', 50000, 1, 1)");
        }
        BankStore store = new BankStore(ds, SqlDialect.SQLITE, "dkbank_", clock::get);
        store.createSchema();
        store.createSchema(); // a second start must not try to upgrade again

        InterestState s = state(store);
        assertEquals($("500.00"), s.balance());
        assertEquals($("500.00"), s.base(), "existing money earns from the start");
        assertEquals(0L, s.lastSeen());
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT meta_value FROM dkbank_meta WHERE meta_key = 'schema_version'")) {
            rs.next();
            assertEquals(String.valueOf(BankStore.SCHEMA_VERSION), rs.getString(1));
        }
    }

    // ------------------------------------------------------------------ online

    @Test
    void anHourOfPlayPaysOnePercent() throws Exception {
        BankStore store = aliceWith("10000");
        for (int i = 0; i < 59; i++) assertFalse(store.beat(beat(MINUTE, true)).paid());
        Payout payout = store.beat(beat(MINUTE, true));
        assertEquals($("100.00"), payout.amount());
        assertEquals($("10100.00"), payout.balance());
        assertEquals($("10100.00"), state(store).base(), "the payout starts earning next cycle");
        assertEquals(0L, state(store).activeMillis());
    }

    @Test
    void timePastThePeriodCarriesOver() throws Exception {
        BankStore store = aliceWith("10000");
        store.beat(beat(50 * MINUTE, true));
        Payout payout = store.beat(beat(15 * MINUTE, true)); // 65 minutes: pay 60, keep 5
        assertEquals($("100.00"), payout.amount());
        assertEquals(5 * MINUTE, state(store).activeMillis());
    }

    @Test
    void afkTimeEarnsTheOfflineRate() throws Exception {
        BankStore store = aliceWith("10000");
        store.beat(beat(30 * MINUTE, true));
        Payout payout = store.beat(beat(30 * MINUTE, false));
        // 30 min active at 1%/h = 50.00; 30 min AFK at 1%/day = 10000 × (1.01^(1/48) − 1) = 2.07
        assertEquals($("52.07"), payout.amount());
    }

    @Test
    void logoutPaysThePartialCycle() throws Exception {
        BankStore store = aliceWith("10000");
        store.beat(beat(10 * MINUTE, true));
        Payout payout = store.settleLogout(beat(5 * MINUTE, true));
        assertEquals($("25.00"), payout.amount()); // 15 minutes
        InterestState s = state(store);
        assertEquals(0L, s.activeMillis());
        assertEquals(clock.get(), s.lastSeen());
    }

    // ------------------------------------------------------------------ lowest balance

    @Test
    void earnsOnTheLowestBalanceOfTheCycle() throws Exception {
        BankStore store = aliceWith("10000");
        store.beat(beat(20 * MINUTE, true));
        store.debit(ALICE, AmountInput.parse("9000"), BigDecimal.ZERO, TransactionType.WITHDRAW, null); // 1000 left
        store.credit(ALICE, $("9000"), TransactionType.DEPOSIT, null, null); // back to 10000
        Payout payout = store.beat(beat(40 * MINUTE, true));
        assertEquals($("10.00"), payout.amount(), "only the 1000 that stayed all hour earns");
    }

    @Test
    void depositsStartEarningAfterTheNextPayout() throws Exception {
        BankStore store = aliceWith("0");
        store.credit(ALICE, $("10000"), TransactionType.DEPOSIT, null, null);
        assertEquals($("0.00"), store.beat(beat(HOUR, true)).amount());
        assertEquals($("100.00"), store.beat(beat(HOUR, true)).amount());
    }

    @Test
    void sendingMoneyLowersTheBase() throws Exception {
        BankStore store = aliceWith("10000");
        UUID bob = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        store.ensureAccount(bob, "Bob");
        store.transfer(ALICE, bob, AmountInput.parse("4000"), BigDecimal.ZERO, null);
        assertEquals($("6000.00"), state(store).base());
        assertEquals($("0.00"), store.interestState(bob).orElseThrow().base());
    }

    // ------------------------------------------------------------------ limits

    @Test
    void capLimitsTheEarningBase() throws Exception {
        BankStore store = aliceWith("2500000");
        assertEquals($("1000.00"), store.beat(beat(HOUR, true, plan("100000", null), null)).amount());
    }

    @Test
    void maxPerPayout() throws Exception {
        BankStore store = aliceWith("10000");
        assertEquals($("40.00"), store.beat(beat(HOUR, true, plan(null, "40"), null)).amount());
    }

    @Test
    void neverPaysPastTheMaxBalance() throws Exception {
        BankStore store = aliceWith("10000");
        assertEquals($("30.00"), store.beat(beat(HOUR, true, PLAN, "10030")).amount());
        assertEquals($("0.00"), store.beat(beat(HOUR, true, PLAN, "10030")).amount());
    }

    // ------------------------------------------------------------------ offline

    @Test
    void offlineDaysCompound() throws Exception {
        BankStore store = aliceWith("10000");
        store.settleLogout(beat(0, true));
        clock.addAndGet(3 * DAY);
        Payout payout = store.settleLogin(ALICE, PLAN, null);
        assertEquals($("303.01"), payout.amount());
        assertEquals(3 * DAY, payout.offlineMillis());
        assertEquals($("10303.01"), state(store).base());
    }

    @Test
    void offlineTimeStopsAtTheMaximum() throws Exception {
        BankStore store = aliceWith("10000");
        store.settleLogout(beat(0, true));
        clock.addAndGet(30 * DAY);
        Payout payout = store.settleLogin(ALICE, PLAN, null);
        assertEquals(7 * DAY, payout.offlineMillis());
        assertEquals($("721.35"), payout.amount()); // 1.01^7 − 1 = 7.2135%
    }

    @Test
    void firstLoginPaysNothing() throws Exception {
        BankStore store = new BankStore(dataSource(), SqlDialect.SQLITE, "dkbank_", clock::get);
        store.createSchema();
        store.ensureAccount(ALICE, "Alice");
        assertFalse(store.settleLogin(ALICE, PLAN, null).paid());
        assertEquals(clock.get(), state(store).lastSeen());
    }

    @Test
    void crashLeftoversArePaidAtTheNextLogin() throws Exception {
        BankStore store = aliceWith("10000");
        store.beat(beat(30 * MINUTE, true)); // then the server crashes: no logout
        clock.addAndGet(DAY);
        Payout payout = store.settleLogin(ALICE, PLAN, null);
        // 30 minutes online (50.00) + one day offline since the last heartbeat (100.00)
        assertEquals($("150.00"), payout.amount());
        assertEquals(0L, state(store).activeMillis());
    }

    @Test
    void loggingInAgainRightAwayPaysNothing() throws Exception {
        BankStore store = aliceWith("10000");
        store.settleLogout(beat(0, true));
        assertFalse(store.settleLogin(ALICE, PLAN, null).paid());
        assertFalse(store.settleLogin(ALICE, PLAN, null).paid());
    }

    @Test
    void interestIsInTheHistory() throws Exception {
        BankStore store = aliceWith("10000");
        store.beat(beat(HOUR, true));
        var entry = store.history(ALICE, 1, 10).entries().getFirst();
        assertEquals(TransactionType.INTEREST, entry.type());
        assertEquals($("100.00"), entry.amount());
        assertEquals("online", entry.actor());
        assertTrue(entry.balanceAfter().compareTo($("10100")) == 0);
    }

    // ------------------------------------------------------------------ hardening

    @Test
    void relogsCantCollectTheMaximumEachTime() throws Exception {
        BankStore store = aliceWith("100000"); // 1% of 100k = 1,000 an hour; at most 120 per hour of payouts
        InterestPlan capped = plan(null, "120");
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < 12; i++) { // 12 logouts after 5 minutes each = one hour
            total = total.add(store.settleLogout(beat(5 * MINUTE, true, capped, null)).amount());
            store.settleLogin(ALICE, capped, null); // straight back in: no time offline
        }
        assertTrue(total.compareTo($("120.00")) <= 0, "an hour of relogs paid " + total);
    }

    @Test
    void anotherServerAlreadyCountedTheTime() throws Exception {
        BankStore store = aliceWith("10000");
        store.beat(beat(MINUTE, true)); // server A
        clock.addAndGet(30_000);
        store.settleLogin(ALICE, PLAN, null); // the player joins server B 30 s later
        clock.addAndGet(30_000);
        // Server A sees them leave a minute after its last heartbeat; B already counted the last 30 s.
        Payout logout = store.settleLogout(new Beat(ALICE, MINUTE, clock.get(), true, PLAN, null));
        assertEquals($("0.83"), logout.amount()); // 30 s at 1%/h of 10,000, not a whole minute (1.66)
    }

    @Test
    void shorterPeriodDoesntPayTwice() throws Exception {
        BankStore store = aliceWith("10000");
        store.beat(beat(50 * MINUTE, true));
        // The owner shortens the online period to 30 minutes; the next heartbeat is AFK.
        InterestPlan half = new InterestPlan(true, ONE, 30 * MINUTE, true, ONE, DAY, 7 * DAY, null, null);
        Payout first = store.beat(beat(MINUTE, false, half, null));
        assertEquals($("100.00"), first.amount(), "one 30-minute period at 1% per 30m, not 1.7");
        assertTrue(first.cycleMillis() < 30 * MINUTE, "carried " + first.cycleMillis());
        InterestState s = state(store);
        assertTrue(s.activeMillis() >= 0 && s.afkMillis() >= 0);
    }

    @Test
    void batchedHeartbeatsMatchSingleOnes() throws Exception {
        BankStore single = aliceWith("10000");
        long at = clock.get();
        Payout one = single.beat(beat(HOUR, true));
        clock.set(at);
        BankStore batched = aliceWith("10000");
        Payout many = batched.beatAll(java.util.List.of(beat(HOUR, true))).getFirst();
        assertEquals(one.amount(), many.amount());
        assertEquals(one.balance(), many.balance());
        assertTrue(batched.beatAll(java.util.List.of()).isEmpty());
    }
}
