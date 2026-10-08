package dev.direk.dkbank.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.direk.dkbank.config.Settings;

import javax.sql.DataSource;
import java.io.File;

/**
 * The connection pool. SQLite and MySQL drivers come with Paper itself; MariaDB uses the MySQL driver.
 */
public final class Database implements AutoCloseable {

    private final HikariDataSource pool;
    private final SqlDialect dialect;

    private Database(HikariDataSource pool, SqlDialect dialect) {
        this.pool = pool;
        this.dialect = dialect;
    }

    public static Database open(Settings.Storage storage, File dataFolder) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("dkBank");
        SqlDialect dialect;

        if (storage.type() == Settings.StorageType.SQLITE) {
            dialect = SqlDialect.SQLITE;
            File file = new File(dataFolder, storage.sqliteFile());
            config.setDriverClassName("org.sqlite.JDBC");
            config.setJdbcUrl("jdbc:sqlite:" + file.getAbsolutePath());
            // One connection: SQLite has a single writer anyway, and this keeps writes strictly in order.
            config.setMaximumPoolSize(1);
            config.addDataSourceProperty("journal_mode", "WAL");        // crash-safe and fast
            config.addDataSourceProperty("synchronous", "FULL");        // a committed change survives power loss
            config.addDataSourceProperty("busy_timeout", "10000");
            config.addDataSourceProperty("transaction_mode", "IMMEDIATE"); // lock at the start of each transaction
        } else {
            dialect = SqlDialect.MYSQL;
            config.setDriverClassName("com.mysql.cj.jdbc.Driver");
            config.setJdbcUrl("jdbc:mysql://" + storage.host() + ":" + storage.port() + "/" + storage.database()
                    + "?useSSL=" + storage.useSsl() + "&allowPublicKeyRetrieval=true&characterEncoding=utf8"
                    + "&useUnicode=true");
            config.setUsername(storage.username());
            config.setPassword(storage.password());
            config.setMaximumPoolSize(storage.poolSize());
            config.setMinimumIdle(Math.min(2, storage.poolSize()));
            config.setTransactionIsolation("TRANSACTION_REPEATABLE_READ");
            config.addDataSourceProperty("cachePrepStmts", "true");
            config.addDataSourceProperty("prepStmtCacheSize", "250");
            config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
            config.addDataSourceProperty("useServerPrepStmts", "true");
        }
        config.setConnectionTimeout(10_000);
        return new Database(new HikariDataSource(config), dialect);
    }

    public DataSource dataSource() {
        return pool;
    }

    public SqlDialect dialect() {
        return dialect;
    }

    /** Threads that run database work: one for SQLite (it serialises writes), more for MySQL. */
    public int workerThreads() {
        return dialect == SqlDialect.SQLITE ? 1 : Math.max(2, Math.min(4, pool.getMaximumPoolSize()));
    }

    @Override
    public void close() {
        pool.close();
    }
}
