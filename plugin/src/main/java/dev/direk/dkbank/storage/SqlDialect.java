package dev.direk.dkbank.storage;

import java.util.List;

/**
 * The few SQL differences between SQLite and MySQL/MariaDB. Everything else is standard SQL.
 */
public enum SqlDialect {

    SQLITE {
        @Override
        List<String> schema(String p) {
            return List.of(
                    "CREATE TABLE IF NOT EXISTS " + p + "meta (meta_key VARCHAR(64) PRIMARY KEY, meta_value VARCHAR(255) NOT NULL)",
                    "CREATE TABLE IF NOT EXISTS " + p + "accounts ("
                            + "uuid CHAR(36) PRIMARY KEY, name VARCHAR(16) NOT NULL, balance BIGINT NOT NULL DEFAULT 0, "
                            + "created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL)",
                    "CREATE INDEX IF NOT EXISTS " + p + "accounts_name ON " + p + "accounts (name COLLATE NOCASE)",
                    "CREATE INDEX IF NOT EXISTS " + p + "accounts_balance ON " + p + "accounts (balance)",
                    "CREATE TABLE IF NOT EXISTS " + p + "transactions ("
                            + "id INTEGER PRIMARY KEY AUTOINCREMENT, account CHAR(36) NOT NULL, type VARCHAR(24) NOT NULL, "
                            + "amount BIGINT NOT NULL, fee BIGINT NOT NULL DEFAULT 0, balance_after BIGINT NOT NULL, "
                            + "other_uuid CHAR(36), other_name VARCHAR(16), actor VARCHAR(36), created_at BIGINT NOT NULL)",
                    "CREATE INDEX IF NOT EXISTS " + p + "transactions_account ON " + p + "transactions (account, id)");
        }

        @Override
        String upsertAccount(String p) {
            return "INSERT INTO " + p + "accounts (uuid, name, balance, created_at, updated_at) VALUES (?, ?, 0, ?, ?) "
                    + "ON CONFLICT(uuid) DO UPDATE SET name = excluded.name";
        }

        @Override
        String nameEquals() {
            return "name = ? COLLATE NOCASE";
        }

        @Override
        String upsertIp(String p) {
            return "INSERT INTO " + p + "ips (ip_hash, uuid, first_seen, last_seen) VALUES (?, ?, ?, ?) "
                    + "ON CONFLICT(ip_hash, uuid) DO UPDATE SET last_seen = excluded.last_seen";
        }

        @Override
        String forUpdate() {
            return ""; // SQLite transactions lock the whole database (dkBank opens them as IMMEDIATE)
        }
    },

    MYSQL {
        @Override
        List<String> schema(String p) {
            return List.of(
                    "CREATE TABLE IF NOT EXISTS " + p + "meta (meta_key VARCHAR(64) PRIMARY KEY, meta_value VARCHAR(255) NOT NULL) "
                            + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
                    "CREATE TABLE IF NOT EXISTS " + p + "accounts ("
                            + "uuid CHAR(36) PRIMARY KEY, name VARCHAR(16) NOT NULL, balance BIGINT NOT NULL DEFAULT 0, "
                            + "created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, "
                            + "INDEX " + p + "accounts_name (name), INDEX " + p + "accounts_balance (balance)) "
                            + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
                    "CREATE TABLE IF NOT EXISTS " + p + "transactions ("
                            + "id BIGINT AUTO_INCREMENT PRIMARY KEY, account CHAR(36) NOT NULL, type VARCHAR(24) NOT NULL, "
                            + "amount BIGINT NOT NULL, fee BIGINT NOT NULL DEFAULT 0, balance_after BIGINT NOT NULL, "
                            + "other_uuid CHAR(36), other_name VARCHAR(16), actor VARCHAR(36), created_at BIGINT NOT NULL, "
                            + "INDEX " + p + "transactions_account (account, id)) "
                            + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        }

        @Override
        String upsertAccount(String p) {
            return "INSERT INTO " + p + "accounts (uuid, name, balance, created_at, updated_at) VALUES (?, ?, 0, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE name = VALUES(name)";
        }

        @Override
        String nameEquals() {
            return "name = ?"; // the default MySQL/MariaDB collation already ignores case
        }

        @Override
        String upsertIp(String p) {
            return "INSERT INTO " + p + "ips (ip_hash, uuid, first_seen, last_seen) VALUES (?, ?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE last_seen = VALUES(last_seen)";
        }

        @Override
        String forUpdate() {
            return " FOR UPDATE"; // lock the rows until the transaction ends
        }
    };

    /** Statements that create the tables (safe to run every start). */
    abstract List<String> schema(String prefix);

    /** Creates an account, or updates the stored name of an existing one. */
    abstract String upsertAccount(String prefix);

    /** Records that an account logged in from an address (by hash), or updates when it last did. */
    abstract String upsertIp(String prefix);

    /** Case-insensitive name match. */
    abstract String nameEquals();

    /** Suffix that locks selected rows for the rest of the transaction. */
    abstract String forUpdate();
}
