package dev.direk.dkbank.storage;

import dev.direk.dkbank.interest.InterestPlan;
import dev.direk.dkbank.money.AmountInput;
import dev.direk.dkbank.storage.StoreTypes.Beat;
import dev.direk.dkbank.storage.StoreTypes.Failure;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The same checks as the SQLite tests, against a real MySQL or MariaDB server. Skipped unless these are set:
 * <pre>
 * DKBANK_TEST_MYSQL_URL=jdbc:mysql://localhost:3306/dkbank_test
 * DKBANK_TEST_MYSQL_USER=root
 * DKBANK_TEST_MYSQL_PASSWORD=secret
 * </pre>
 * Each test uses its own table prefix and drops its tables afterwards.
 */
class MySqlStoreTest {

    private static final String URL = System.getenv("DKBANK_TEST_MYSQL_URL");
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private final String prefix = "dkt" + Long.toString(System.nanoTime() % 1_000_000_000L, 36) + "_";

    private static BigDecimal $(String value) {
        return new BigDecimal(value);
    }

    /** A plain DataSource on DriverManager, so the test needs nothing but the driver. */
    private static DataSource dataSource() {
        String user = System.getenv().getOrDefault("DKBANK_TEST_MYSQL_USER", "root");
        String password = System.getenv().getOrDefault("DKBANK_TEST_MYSQL_PASSWORD", "");
        return new DataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                Connection c = DriverManager.getConnection(URL, user, password);
                c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                return c;
            }

            @Override
            public Connection getConnection(String u, String p) throws SQLException {
                return DriverManager.getConnection(URL, u, p);
            }

            @Override
            public PrintWriter getLogWriter() {
                return null;
            }

            @Override
            public void setLogWriter(PrintWriter out) {
            }

            @Override
            public void setLoginTimeout(int seconds) {
            }

            @Override
            public int getLoginTimeout() {
                return 0;
            }

            @Override
            public Logger getParentLogger() {
                return Logger.getGlobal();
            }

            @Override
            public <T> T unwrap(Class<T> iface) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean isWrapperFor(Class<?> iface) {
                return false;
            }
        };
    }

    private BankStore store() {
        assumeTrue(URL != null && !URL.isBlank(), "DKBANK_TEST_MYSQL_URL isn't set");
        BankStore store = new BankStore(dataSource(), SqlDialect.MYSQL, prefix, clock::incrementAndGet);
        store.createSchema();
        store.ensureAccount(A, "Alice");
        store.ensureAccount(B, "Bob");
        return store;
    }

    private void dropTables() throws SQLException {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            for (String table : List.of("meta", "accounts", "transactions", "ips")) st.execute("DROP TABLE IF EXISTS " + prefix + table);
        }
    }

    @Test
    void everythingWorksOnMySql() throws Exception {
        BankStore store = store();
        try {
            store.createSchema(); // a second start upgrades nothing
            assertTrue(store.credit(A, $("1000"), TransactionType.DEPOSIT, null, null).ok());
            assertEquals($("990.00"), store.debit(A, AmountInput.parse("10"), $("0"), TransactionType.WITHDRAW, null).balance());
            assertEquals(Failure.INSUFFICIENT_FUNDS, store.debit(A, AmountInput.parse("5000"), $("0"), TransactionType.WITHDRAW, null).failure());
            assertEquals($("490.00"), store.transfer(A, B, AmountInput.parse("half"), $("0"), null).sender().balance());
            assertEquals("Bob", store.accountByName("BOB").orElseThrow().name());
            assertTrue(store.upgrade(B, null, "silver", $("100")).ok());
            assertEquals("silver", store.account(B).orElseThrow().tier());

            InterestPlan plan = new InterestPlan(true, BigDecimal.ONE, 3_600_000L, true, BigDecimal.ONE, 86_400_000L, 604_800_000L, null, null);
            store.settleLogin(A, plan, null);
            assertEquals($("4.90"), store.beat(new Beat(A, 3_600_000L, clock.addAndGet(3_600_000L), true, plan, null)).amount());

            assertEquals(2, store.recordLogin(B, "home", 0).size() + store.recordLogin(A, "home", 0).size() - 1);
            assertEquals(64, store.ipSalt().length());
            assertEquals(2, store.top(10).size());
            assertTrue(store.totals().balance().signum() > 0);
            assertTrue(!store.activity(0).isEmpty());
        } finally {
            dropTables();
        }
    }

    /** Opposite transfers from many threads: row locks keep every cent, and no deadlock is left hanging. */
    @Test
    void concurrentTransfersKeepEveryCent() throws Exception {
        BankStore store = store();
        try {
            store.credit(A, $("10000"), TransactionType.DEPOSIT, null, null);
            store.credit(B, $("10000"), TransactionType.DEPOSIT, null, null);
            ExecutorService pool = Executors.newFixedThreadPool(8);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                boolean even = i % 2 == 0;
                futures.add(pool.submit(() -> store.transfer(even ? A : B, even ? B : A, AmountInput.parse("7.77"), $("0"), null)));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception e) {
                    // a deadlock victim rolls back completely: fine, it just didn't happen
                }
            }
            pool.shutdown();
            BigDecimal total = store.account(A).orElseThrow().balance().add(store.account(B).orElseThrow().balance());
            assertEquals($("20000.00"), total);
        } finally {
            dropTables();
        }
    }

    /** Tables left half-upgraded by a crash (MySQL saves each change on its own) upgrade cleanly. */
    @Test
    void halfDoneUpgradeIsFinished() throws Exception {
        assumeTrue(URL != null && !URL.isBlank(), "DKBANK_TEST_MYSQL_URL isn't set");
        try {
            try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
                for (String sql : SqlDialect.MYSQL.schema(prefix)) st.execute(sql);
                st.execute("INSERT INTO " + prefix + "meta VALUES ('schema_version', '1')");
                st.execute("ALTER TABLE " + prefix + "accounts ADD COLUMN last_seen BIGINT NOT NULL DEFAULT 0"); // part of v2
            }
            BankStore store = new BankStore(dataSource(), SqlDialect.MYSQL, prefix, clock::incrementAndGet);
            store.createSchema();
            try (Connection c = dataSource().getConnection(); Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT meta_value FROM " + prefix + "meta WHERE meta_key = 'schema_version'")) {
                rs.next();
                assertEquals(String.valueOf(BankStore.SCHEMA_VERSION), rs.getString(1));
            }
        } finally {
            dropTables();
        }
    }
}
