package dev.direk.dkbank.storage;

import dev.direk.dkbank.interest.InterestMath;
import dev.direk.dkbank.interest.InterestPlan;
import dev.direk.dkbank.money.AmountInput;
import dev.direk.dkbank.money.Money;
import dev.direk.dkbank.storage.StoreTypes.Account;
import dev.direk.dkbank.storage.StoreTypes.Activity;
import dev.direk.dkbank.storage.StoreTypes.Earner;
import dev.direk.dkbank.storage.StoreTypes.TopEntry;
import dev.direk.dkbank.storage.StoreTypes.Totals;
import dev.direk.dkbank.storage.StoreTypes.AltAccount;
import dev.direk.dkbank.storage.StoreTypes.Entry;
import dev.direk.dkbank.storage.StoreTypes.Beat;
import dev.direk.dkbank.storage.StoreTypes.Failure;
import dev.direk.dkbank.storage.StoreTypes.InterestState;
import dev.direk.dkbank.storage.StoreTypes.Payout;
import dev.direk.dkbank.storage.StoreTypes.ReceiverLimit;
import dev.direk.dkbank.storage.StoreTypes.Limits;
import dev.direk.dkbank.storage.StoreTypes.Page;
import dev.direk.dkbank.storage.StoreTypes.Result;
import dev.direk.dkbank.storage.StoreTypes.TransferResult;
import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Every read and write of bank data. Each money operation is one database transaction: the balance
 * check, the balance change and its history line either all happen or none do.
 * <p>
 * Rows are locked for the whole transaction (SQLite: the database, via IMMEDIATE transactions;
 * MySQL: {@code SELECT ... FOR UPDATE}), so two operations on the same account can never both spend
 * the same money, even from different servers sharing one MySQL database.
 * <p>
 * All methods block. Call them off the server's main thread.
 */
public final class BankStore {

    /** Version of the table layout, stored in the meta table, for future migrations. */
    public static final int SCHEMA_VERSION = 5;

    private final DataSource dataSource;
    private final SqlDialect dialect;
    private final String p;
    private final LongSupplier clock;

    public BankStore(DataSource dataSource, SqlDialect dialect, String tablePrefix, LongSupplier clock) {
        this.dataSource = dataSource;
        this.dialect = dialect;
        this.p = tablePrefix;
        this.clock = clock;
    }

    /** Thrown when the database fails. Nothing was changed. */
    public static final class StorageException extends RuntimeException {
        StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @FunctionalInterface
    private interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    private <T> T transaction(String what, Work<T> work) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new StorageException("Database error while trying to " + what, e);
        }
    }

    // ------------------------------------------------------------------ setup

    /**
     * Creates the tables if they don't exist yet, then upgrades them to the current layout. Upgrades are
     * applied one version at a time, each in its own transaction, so a failed upgrade can be retried.
     */
    public void createSchema() {
        transaction("create the tables", c -> {
            try (Statement st = c.createStatement()) {
                for (String sql : dialect.schema(p)) st.execute(sql);
            }
            if (schemaVersion(c) == 0) setSchemaVersion(c, 1, true); // fresh tables are version 1
            return null;
        });
        while (true) {
            int version = transaction("read the table version", this::schemaVersionOf);
            if (version >= SCHEMA_VERSION) return;
            int next = version + 1;
            transaction("upgrade the tables to version " + next, c -> {
                try (Statement st = c.createStatement()) {
                    for (String sql : migration(next)) st.execute(sql);
                }
                setSchemaVersion(c, next, false);
                return null;
            });
        }
    }

    /** Statements that upgrade the tables from {@code version - 1} to {@code version}. */
    private List<String> migration(int version) {
        return switch (version) {
            // 0.2.0: interest. last_seen: when offline time starts counting. cycle_*: progress towards the
            // next online payout. interest_base: lowest balance since the last payout.
            case 2 -> List.of(
                    "ALTER TABLE " + p + "accounts ADD COLUMN last_seen BIGINT NOT NULL DEFAULT 0",
                    "ALTER TABLE " + p + "accounts ADD COLUMN cycle_active_ms BIGINT NOT NULL DEFAULT 0",
                    "ALTER TABLE " + p + "accounts ADD COLUMN cycle_afk_ms BIGINT NOT NULL DEFAULT 0",
                    "ALTER TABLE " + p + "accounts ADD COLUMN interest_base BIGINT NOT NULL DEFAULT 0",
                    "UPDATE " + p + "accounts SET interest_base = balance");
            // 0.3.0: bank tiers. tier: the bought tier's id, null for the first tier.
            case 3 -> List.of("ALTER TABLE " + p + "accounts ADD COLUMN tier VARCHAR(16) NULL");
            // 0.4.0: alt-account limit. ips: which accounts logged in from which address (salted hashes,
            // never the address itself). alt_exempt: staff allowed the account whatever the limit.
            case 4 -> List.of(
                    "CREATE TABLE IF NOT EXISTS " + p + "ips (ip_hash CHAR(64) NOT NULL, uuid CHAR(36) NOT NULL, "
                            + "first_seen BIGINT NOT NULL, last_seen BIGINT NOT NULL, PRIMARY KEY (ip_hash, uuid))",
                    "ALTER TABLE " + p + "accounts ADD COLUMN alt_exempt INT NOT NULL DEFAULT 0",
                    "CREATE INDEX " + p + "ips_uuid ON " + p + "ips (uuid, last_seen)");
            // 0.5.0: economy report, which sums transactions by time.
            case 5 -> List.of("CREATE INDEX " + p + "transactions_time ON " + p + "transactions (created_at)");
            default -> throw new IllegalStateException("No upgrade to table version " + version);
        };
    }

    private int schemaVersionOf(Connection c) throws SQLException {
        return schemaVersion(c);
    }

    private int schemaVersion(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT meta_value FROM " + p + "meta WHERE meta_key = 'schema_version'");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? Integer.parseInt(rs.getString(1)) : 0;
        }
    }

    private void setSchemaVersion(Connection c, int version, boolean insert) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(insert
                ? "INSERT INTO " + p + "meta (meta_key, meta_value) VALUES ('schema_version', ?)"
                : "UPDATE " + p + "meta SET meta_value = ? WHERE meta_key = 'schema_version'")) {
            ps.setString(1, String.valueOf(version));
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ accounts

    /** Creates the account if needed and keeps its name up to date. */
    public Account ensureAccount(UUID uuid, String name) {
        return transaction("load " + name + "'s account", c -> {
            long now = clock.getAsLong();
            try (PreparedStatement ps = c.prepareStatement(dialect.upsertAccount(p))) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.setLong(3, now);
                ps.setLong(4, now);
                ps.executeUpdate();
            }
            return readAccount(c, uuid, false).orElseThrow();
        });
    }

    public Optional<Account> account(UUID uuid) {
        return transaction("read an account", c -> readAccount(c, uuid, false));
    }

    /** Looks up an account by player name, ignoring case. The most recently active one wins. */
    public Optional<Account> accountByName(String name) {
        return transaction("find " + name + "'s account", c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, name, balance, tier FROM " + p + "accounts WHERE "
                    + dialect.nameEquals() + " ORDER BY updated_at DESC LIMIT 1")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(account(rs)) : Optional.empty();
                }
            }
        });
    }

    // ------------------------------------------------------------------ money in

    /**
     * Adds money to an account (a deposit, an admin give or a refund).
     *
     * @param maxBalance the account's maximum balance, or null for no limit other than {@link Money#HARD_MAX}
     */
    public Result credit(UUID uuid, BigDecimal amount, TransactionType type, @Nullable BigDecimal maxBalance,
                         @Nullable String actor) {
        return transaction("add money to an account", c -> {
            Optional<Account> account = readAccount(c, uuid, true);
            if (account.isEmpty()) return Result.fail(Failure.NO_ACCOUNT, Money.ZERO);
            BigDecimal balance = account.get().balance();
            BigDecimal after = balance.add(amount);
            if (after.compareTo(limit(maxBalance)) > 0) return Result.fail(Failure.BALANCE_LIMIT, balance);

            writeBalance(c, uuid, after, false);
            log(c, uuid, type, amount, Money.ZERO, after, null, null, actor);
            return Result.ok(amount, Money.ZERO, after);
        });
    }

    // ------------------------------------------------------------------ money out

    /**
     * Takes money out of an account (a withdrawal or an admin take). "all" and "half" are worked out
     * against the balance inside the transaction.
     *
     * @param feePercent fee taken from the amount (the account loses {@code amount}; the fee is part of it)
     */
    public Result debit(UUID uuid, AmountInput input, BigDecimal feePercent, TransactionType type,
                        @Nullable String actor) {
        return debit(uuid, input, feePercent, type, actor, Limits.NONE);
    }

    /** Like {@link #debit(UUID, AmountInput, BigDecimal, TransactionType, String)}, with amount limits. */
    public Result debit(UUID uuid, AmountInput input, BigDecimal feePercent, TransactionType type,
                        @Nullable String actor, Limits limits) {
        return transaction("take money from an account", c -> {
            Optional<Account> account = readAccount(c, uuid, true);
            if (account.isEmpty()) return Result.fail(Failure.NO_ACCOUNT, Money.ZERO);
            BigDecimal balance = account.get().balance();
            BigDecimal amount = input.resolve(balance);
            if (input.isShare() && limits.cap() != null) amount = amount.min(limits.cap());
            if (amount.signum() <= 0) {
                return Result.fail(balance.signum() <= 0 ? Failure.INSUFFICIENT_FUNDS : Failure.NOTHING_TO_MOVE, balance);
            }
            if (amount.compareTo(balance) > 0) return Result.fail(Failure.INSUFFICIENT_FUNDS, balance);
            if (amount.compareTo(limits.min()) < 0) return Result.fail(Failure.BELOW_MINIMUM, balance);

            BigDecimal fee = Money.fee(amount, feePercent).min(amount);
            BigDecimal after = balance.subtract(amount);
            writeBalance(c, uuid, after, true);
            log(c, uuid, type, amount, fee, after, null, null, actor);
            return Result.ok(amount, fee, after);
        });
    }

    /** Sets an account's balance (admin). */
    public Result set(UUID uuid, BigDecimal balance, @Nullable String actor) {
        return transaction("set a balance", c -> {
            Optional<Account> account = readAccount(c, uuid, true);
            if (account.isEmpty()) return Result.fail(Failure.NO_ACCOUNT, Money.ZERO);
            if (balance.compareTo(Money.HARD_MAX) > 0) return Result.fail(Failure.BALANCE_LIMIT, account.get().balance());
            writeBalance(c, uuid, balance, true);
            log(c, uuid, TransactionType.ADMIN_SET, balance, Money.ZERO, balance, null, null, actor);
            return Result.ok(balance, Money.ZERO, balance);
        });
    }

    // ------------------------------------------------------------------ transfers

    /**
     * Moves money from one account to another in a single transaction. The sender pays the fee on top
     * of the amount; the receiver gets the amount. "all" and "half" leave room for the fee.
     */
    public TransferResult transfer(UUID from, UUID to, AmountInput input, BigDecimal feePercent,
                                   @Nullable BigDecimal receiverMax) {
        return transfer(from, to, input, feePercent, receiverMax, Limits.NONE);
    }

    /** Like {@link #transfer(UUID, UUID, AmountInput, BigDecimal, BigDecimal)}, with amount limits. */
    public TransferResult transfer(UUID from, UUID to, AmountInput input, BigDecimal feePercent,
                                   @Nullable BigDecimal receiverMax, Limits limits) {
        return transfer(from, to, input, feePercent, receiver -> receiverMax, limits);
    }

    /** Like {@link #transfer(UUID, UUID, AmountInput, BigDecimal, BigDecimal, Limits)}, with the receiver's
     * maximum balance worked out from their account (e.g. from their tier). */
    public TransferResult transfer(UUID from, UUID to, AmountInput input, BigDecimal feePercent,
                                   ReceiverLimit receiverLimit, Limits limits) {
        return transaction("transfer money", c -> {
            // Lock both rows in a fixed order so two opposite transfers can't deadlock.
            lockBoth(c, from, to);
            Optional<Account> sender = readAccount(c, from, false);
            Optional<Account> receiver = readAccount(c, to, false);
            if (sender.isEmpty() || receiver.isEmpty()) {
                return new TransferResult(Result.fail(Failure.NO_ACCOUNT, sender.map(Account::balance).orElse(Money.ZERO)),
                        Money.ZERO, receiver.map(Account::name).orElse("?"));
            }
            BigDecimal balance = sender.get().balance();
            String receiverName = receiver.get().name();

            BigDecimal amount = input.isShare()
                    ? Money.largestWithFee(input.resolve(balance), feePercent)
                    : input.resolve(balance);
            if (input.isShare() && limits.cap() != null) amount = amount.min(limits.cap());
            BigDecimal fee = Money.fee(amount, feePercent);
            BigDecimal total = amount.add(fee);
            if (amount.signum() <= 0) {
                Failure why = balance.signum() <= 0 ? Failure.INSUFFICIENT_FUNDS : Failure.NOTHING_TO_MOVE;
                return new TransferResult(Result.fail(why, balance), receiver.get().balance(), receiverName);
            }
            if (total.compareTo(balance) > 0) {
                return new TransferResult(Result.fail(Failure.INSUFFICIENT_FUNDS, balance), receiver.get().balance(), receiverName);
            }
            if (amount.compareTo(limits.min()) < 0) {
                return new TransferResult(Result.fail(Failure.BELOW_MINIMUM, balance), receiver.get().balance(), receiverName);
            }
            BigDecimal receiverAfter = receiver.get().balance().add(amount);
            if (receiverAfter.compareTo(limit(receiverLimit.maxBalance(receiver.get()))) > 0) {
                return new TransferResult(Result.fail(Failure.BALANCE_LIMIT, balance), receiver.get().balance(), receiverName);
            }

            BigDecimal senderAfter = balance.subtract(total);
            writeBalance(c, from, senderAfter, true);
            writeBalance(c, to, receiverAfter, false);
            log(c, from, TransactionType.TRANSFER_OUT, amount, fee, senderAfter, to, receiverName, null);
            log(c, to, TransactionType.TRANSFER_IN, amount, Money.ZERO, receiverAfter, from, sender.get().name(), null);
            return new TransferResult(Result.ok(amount, fee, senderAfter), receiverAfter, receiverName);
        });
    }

    // ------------------------------------------------------------------ tiers

    /**
     * Buys a tier: takes the cost from the bank and stores the new tier, in one transaction.
     *
     * @param expectedTier the bought tier the offer was based on (null for the first tier); if it changed in
     *                     the meantime, nothing happens and {@link Failure#TIER_CHANGED} is returned
     */
    public Result upgrade(UUID uuid, @Nullable String expectedTier, String newTier, BigDecimal cost) {
        return transaction("upgrade a bank tier", c -> {
            Optional<Account> account = readAccount(c, uuid, true);
            if (account.isEmpty()) return Result.fail(Failure.NO_ACCOUNT, Money.ZERO);
            BigDecimal balance = account.get().balance();
            if (!Objects.equals(account.get().tier(), expectedTier)) return Result.fail(Failure.TIER_CHANGED, balance);
            if (cost.compareTo(balance) > 0) return Result.fail(Failure.INSUFFICIENT_FUNDS, balance);

            BigDecimal after = balance.subtract(cost);
            writeBalance(c, uuid, after, true);
            writeTier(c, uuid, newTier);
            log(c, uuid, TransactionType.UPGRADE, cost, Money.ZERO, after, null, newTier, null);
            return Result.ok(cost, Money.ZERO, after);
        });
    }

    /** Sets an account's bought tier (admin). Null puts it back on the first tier. */
    public Result setTier(UUID uuid, @Nullable String tier, @Nullable String actor) {
        return transaction("set a bank tier", c -> {
            Optional<Account> account = readAccount(c, uuid, true);
            if (account.isEmpty()) return Result.fail(Failure.NO_ACCOUNT, Money.ZERO);
            BigDecimal balance = account.get().balance();
            writeTier(c, uuid, tier);
            log(c, uuid, TransactionType.TIER_SET, Money.ZERO, Money.ZERO, balance, null, tier, actor);
            return Result.ok(Money.ZERO, Money.ZERO, balance);
        });
    }

    private void writeTier(Connection c, UUID uuid, @Nullable String tier) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE " + p + "accounts SET tier = ?, updated_at = ? WHERE uuid = ?")) {
            if (tier == null) ps.setNull(1, Types.VARCHAR); else ps.setString(1, tier);
            ps.setLong(2, clock.getAsLong());
            ps.setString(3, uuid.toString());
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ leaderboard and reports

    /** The richest accounts, richest first (ties: the name decides). */
    public List<TopEntry> top(int limit) {
        return transaction("read the leaderboard", c -> {
            List<TopEntry> result = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, name, balance FROM " + p + "accounts "
                    + "WHERE balance > 0 ORDER BY balance DESC, name LIMIT ?")) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.add(new TopEntry(UUID.fromString(rs.getString(1)), rs.getString(2), Money.fromCents(rs.getLong(3))));
                    }
                }
            }
            return result;
        });
    }

    /** Number of accounts and all their money together. */
    public Totals totals() {
        return transaction("add up all accounts", c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*), COALESCE(SUM(balance), 0) FROM " + p + "accounts");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new Totals(rs.getLong(1), Money.fromCents(rs.getLong(2)));
            }
        });
    }

    /** Count, amount and fees of every kind of transaction since {@code since}. */
    public List<Activity> activity(long since) {
        return transaction("add up recent transactions", c -> {
            List<Activity> result = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT type, COUNT(*), COALESCE(SUM(amount), 0), "
                    + "COALESCE(SUM(fee), 0) FROM " + p + "transactions WHERE created_at >= ? GROUP BY type")) {
                ps.setLong(1, since);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        TransactionType type;
                        try {
                            type = TransactionType.valueOf(rs.getString(1));
                        } catch (IllegalArgumentException e) {
                            continue; // written by a newer dkBank
                        }
                        result.add(new Activity(type, rs.getLong(2), Money.fromCents(rs.getLong(3)), Money.fromCents(rs.getLong(4))));
                    }
                }
            }
            return result;
        });
    }

    /** The accounts with the most of one kind of transaction since {@code since}, e.g. top interest earners. */
    public List<Earner> topBy(TransactionType type, long since, int limit) {
        return transaction("find top earners", c -> {
            List<Earner> result = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT a.name, SUM(t.amount) AS total FROM " + p + "transactions t "
                    + "JOIN " + p + "accounts a ON a.uuid = t.account WHERE t.type = ? AND t.created_at >= ? "
                    + "GROUP BY a.uuid, a.name ORDER BY total DESC LIMIT ?")) {
                ps.setString(1, type.name());
                ps.setLong(2, since);
                ps.setInt(3, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) result.add(new Earner(rs.getString(1), Money.fromCents(rs.getLong(2))));
                }
            }
            return result;
        });
    }

    // ------------------------------------------------------------------ alt accounts

    /** The secret mixed into IP hashes, created once per database. */
    public String ipSalt() {
        try {
            return transaction("create the IP salt", c -> {
                String salt = readMeta(c, "ip_salt");
                if (salt != null) return salt;
                byte[] bytes = new byte[32];
                new java.security.SecureRandom().nextBytes(bytes);
                String created = java.util.HexFormat.of().formatHex(bytes);
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + p + "meta (meta_key, meta_value) VALUES ('ip_salt', ?)")) {
                    ps.setString(1, created);
                    ps.executeUpdate();
                }
                return created;
            });
        } catch (StorageException e) {
            // Another server sharing the database created it at the same moment.
            String salt = transaction("read the IP salt", c -> readMeta(c, "ip_salt"));
            if (salt == null) throw e;
            return salt;
        }
    }

    private @Nullable String readMeta(Connection c, String key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT meta_value FROM " + p + "meta WHERE meta_key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /**
     * Records a login from an address, then lists every account seen on it since {@code since}, in the
     * order they first used it.
     */
    public List<AltAccount> recordLogin(UUID uuid, String ipHash, long since) {
        return transaction("record a login address", c -> {
            long now = clock.getAsLong();
            try (PreparedStatement ps = c.prepareStatement(dialect.upsertIp(p))) {
                ps.setString(1, ipHash);
                ps.setString(2, uuid.toString());
                ps.setLong(3, now);
                ps.setLong(4, now);
                ps.executeUpdate();
            }
            return accountsOnIp(c, ipHash, since);
        });
    }

    /** Every account seen on an address since {@code since}, in the order they first used it. */
    public List<AltAccount> accountsOnIp(String ipHash, long since) {
        return transaction("list accounts on an address", c -> accountsOnIp(c, ipHash, since));
    }

    private List<AltAccount> accountsOnIp(Connection c, String ipHash, long since) throws SQLException {
        List<AltAccount> result = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT i.uuid, a.name, i.first_seen, i.last_seen, a.alt_exempt FROM "
                + p + "ips i LEFT JOIN " + p + "accounts a ON a.uuid = i.uuid WHERE i.ip_hash = ? AND i.last_seen >= ? "
                + "ORDER BY i.first_seen, i.uuid")) {
            ps.setString(1, ipHash);
            ps.setLong(2, since);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(2);
                    result.add(new AltAccount(UUID.fromString(rs.getString(1)), name == null ? "?" : name,
                            rs.getLong(3), rs.getLong(4), rs.getInt(5) != 0));
                }
            }
        }
        return result;
    }

    /** The address hash an account used most recently, if any. */
    public Optional<String> latestIp(UUID uuid) {
        return transaction("read a login address", c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT ip_hash FROM " + p + "ips WHERE uuid = ? "
                    + "ORDER BY last_seen DESC LIMIT 1")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getString(1)) : Optional.<String>empty();
                }
            }
        });
    }

    /** Lets an account use the bank whatever the alt limit (or stops that). @return false if no account */
    public boolean setAltExempt(UUID uuid, boolean exempt) {
        return transaction("change an alt exemption", c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + p + "accounts SET alt_exempt = ? WHERE uuid = ?")) {
                ps.setInt(1, exempt ? 1 : 0);
                ps.setString(2, uuid.toString());
                return ps.executeUpdate() > 0;
            }
        });
    }

    /** Forgets logins older than {@code before}. @return how many were removed */
    public int pruneIps(long before) {
        return transaction("forget old login addresses", c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + p + "ips WHERE last_seen < ?")) {
                ps.setLong(1, before);
                return ps.executeUpdate();
            }
        });
    }

    // ------------------------------------------------------------------ interest

    /** The interest-related state of an account, or empty if it doesn't exist. */
    public Optional<InterestState> interestState(UUID uuid) {
        return transaction("read interest progress", c -> readInterest(c, uuid, false));
    }

    /**
     * At login: pays any payout cycle left over from last time (e.g. after a crash) plus interest for the
     * time offline, then starts a fresh cycle.
     */
    public Payout settleLogin(UUID uuid, InterestPlan plan, @Nullable BigDecimal maxBalance) {
        return transaction("pay offline interest", c -> {
            Optional<InterestState> found = readInterest(c, uuid, true);
            if (found.isEmpty()) return Payout.none(Money.ZERO);
            InterestState row = found.get();
            long now = clock.getAsLong();
            long offline = row.lastSeen() > 0 ? Math.min(Math.max(0, now - row.lastSeen()), plan.offlineMaxMillis()) : 0;

            BigDecimal base = InterestMath.earningBase(row.base(), plan.cap());
            BigDecimal interest = cycleInterest(base, row.activeMillis(), row.afkMillis(), plan);
            if (plan.offlineEnabled()) {
                interest = interest.add(InterestMath.compounding(base, plan.offlineRate(), offline, plan.offlinePeriodMillis()));
            }
            BigDecimal paid = InterestMath.limit(interest, plan.maxPerPayout(), limit(maxBalance).subtract(row.balance()));
            BigDecimal after = row.balance().add(paid);
            writeInterest(c, uuid, after, after, 0, 0, now);
            if (paid.signum() > 0) log(c, uuid, TransactionType.INTEREST, paid, Money.ZERO, after, null, null, "offline");
            return new Payout(paid, offline, after, 0);
        });
    }

    /**
     * Every minute for each online player: adds the time to the current payout cycle, and pays it once
     * the cycle reaches a full online period. Active time earns the online rate, AFK time the offline rate.
     */
    public Payout beat(Beat beat) {
        return transaction("update interest progress", c -> {
            Optional<InterestState> found = readInterest(c, beat.uuid(), true);
            if (found.isEmpty()) return Payout.none(Money.ZERO);
            InterestState row = found.get();
            long now = clock.getAsLong();
            InterestPlan plan = beat.plan();
            long elapsed = Math.max(0, beat.elapsedMillis());
            long active = row.activeMillis() + (beat.active() ? elapsed : 0);
            long afk = row.afkMillis() + (beat.active() ? 0 : elapsed);

            if (active + afk < plan.onlinePeriodMillis()) {
                writeInterest(c, beat.uuid(), row.balance(), row.base(), active, afk, now);
                return new Payout(Money.ZERO, 0, row.balance(), active + afk);
            }

            // Pay exactly one period; the few seconds past it start the next cycle.
            long excess = active + afk - plan.onlinePeriodMillis();
            if (beat.active()) active -= excess; else afk -= excess;
            BigDecimal base = InterestMath.earningBase(row.base(), plan.cap());
            BigDecimal paid = InterestMath.limit(cycleInterest(base, active, afk, plan), plan.maxPerPayout(),
                    limit(beat.maxBalance()).subtract(row.balance()));
            BigDecimal after = row.balance().add(paid);
            writeInterest(c, beat.uuid(), after, after, beat.active() ? excess : 0, beat.active() ? 0 : excess, now);
            if (paid.signum() > 0) log(c, beat.uuid(), TransactionType.INTEREST, paid, Money.ZERO, after, null, null, "online");
            return new Payout(paid, 0, after, excess);
        });
    }

    /** At logout: adds the last bit of time and pays the unfinished cycle, prorated. */
    public Payout settleLogout(Beat beat) {
        return transaction("pay interest at logout", c -> {
            Optional<InterestState> found = readInterest(c, beat.uuid(), true);
            if (found.isEmpty()) return Payout.none(Money.ZERO);
            InterestState row = found.get();
            long elapsed = Math.max(0, beat.elapsedMillis());
            long active = row.activeMillis() + (beat.active() ? elapsed : 0);
            long afk = row.afkMillis() + (beat.active() ? 0 : elapsed);

            InterestPlan plan = beat.plan();
            BigDecimal base = InterestMath.earningBase(row.base(), plan.cap());
            BigDecimal paid = InterestMath.limit(cycleInterest(base, active, afk, plan), plan.maxPerPayout(),
                    limit(beat.maxBalance()).subtract(row.balance()));
            BigDecimal after = row.balance().add(paid);
            writeInterest(c, beat.uuid(), after, after, 0, 0, clock.getAsLong());
            if (paid.signum() > 0) log(c, beat.uuid(), TransactionType.INTEREST, paid, Money.ZERO, after, null, null, "online");
            return new Payout(paid, 0, after, 0);
        });
    }

    /** Interest for one cycle: active time at the online rate, AFK time at the offline rate. */
    private static BigDecimal cycleInterest(BigDecimal base, long activeMillis, long afkMillis, InterestPlan plan) {
        BigDecimal interest = Money.ZERO;
        if (plan.onlineEnabled()) {
            interest = interest.add(InterestMath.online(base, plan.onlineRate(), activeMillis, plan.onlinePeriodMillis()));
        } else {
            afkMillis += activeMillis; // with online interest off, all time online earns the offline rate
        }
        if (plan.offlineEnabled()) {
            interest = interest.add(InterestMath.compounding(base, plan.offlineRate(), afkMillis, plan.offlinePeriodMillis()));
        }
        return interest;
    }

    private Optional<InterestState> readInterest(Connection c, UUID uuid, boolean lock) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT balance, interest_base, cycle_active_ms, cycle_afk_ms, "
                + "last_seen FROM " + p + "accounts WHERE uuid = ?" + (lock ? dialect.forUpdate() : ""))) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new InterestState(Money.fromCents(rs.getLong(1)), Money.fromCents(rs.getLong(2)),
                        rs.getLong(3), rs.getLong(4), rs.getLong(5)));
            }
        }
    }

    private void writeInterest(Connection c, UUID uuid, BigDecimal balance, BigDecimal base, long active, long afk,
                               long lastSeen) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE " + p + "accounts SET balance = ?, interest_base = ?, "
                + "cycle_active_ms = ?, cycle_afk_ms = ?, last_seen = ?, updated_at = ? WHERE uuid = ?")) {
            ps.setLong(1, Money.toCents(balance));
            ps.setLong(2, Money.toCents(base));
            ps.setLong(3, active);
            ps.setLong(4, afk);
            ps.setLong(5, lastSeen);
            ps.setLong(6, clock.getAsLong());
            ps.setString(7, uuid.toString());
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ history

    /** @param page 1-based page number; out-of-range pages are clamped */
    public Page history(UUID uuid, int page, int pageSize) {
        return transaction("read the history", c -> {
            long total;
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + p + "transactions WHERE account = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    total = rs.getLong(1);
                }
            }
            int pages = (int) Math.max(1, (total + pageSize - 1) / pageSize);
            int current = Math.min(Math.max(1, page), pages);

            List<Entry> entries = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, type, amount, fee, balance_after, other_uuid, "
                    + "other_name, actor, created_at FROM " + p + "transactions WHERE account = ? "
                    + "ORDER BY id DESC LIMIT ? OFFSET ?")) {
                ps.setString(1, uuid.toString());
                ps.setInt(2, pageSize);
                ps.setLong(3, (long) (current - 1) * pageSize);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String other = rs.getString("other_uuid");
                        entries.add(new Entry(rs.getLong("id"), TransactionType.valueOf(rs.getString("type")),
                                Money.fromCents(rs.getLong("amount")), Money.fromCents(rs.getLong("fee")),
                                Money.fromCents(rs.getLong("balance_after")),
                                other == null ? null : UUID.fromString(other), rs.getString("other_name"),
                                rs.getString("actor"), rs.getLong("created_at")));
                    }
                }
            }
            return new Page(entries, current, pages, total);
        });
    }

    // ------------------------------------------------------------------ helpers

    private static BigDecimal limit(@Nullable BigDecimal maxBalance) {
        return maxBalance == null || maxBalance.signum() <= 0 ? Money.HARD_MAX : maxBalance.min(Money.HARD_MAX);
    }

    private Optional<Account> readAccount(Connection c, UUID uuid, boolean lock) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT uuid, name, balance, tier FROM " + p + "accounts WHERE uuid = ?"
                + (lock ? dialect.forUpdate() : ""))) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(account(rs)) : Optional.empty();
            }
        }
    }

    private void lockBoth(Connection c, UUID a, UUID b) throws SQLException {
        String lock = dialect.forUpdate();
        if (lock.isEmpty()) return;
        try (PreparedStatement ps = c.prepareStatement("SELECT uuid FROM " + p + "accounts WHERE uuid IN (?, ?) ORDER BY uuid" + lock)) {
            ps.setString(1, a.toString());
            ps.setString(2, b.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    // reading the rows is what locks them
                }
            }
        }
    }

    private static Account account(ResultSet rs) throws SQLException {
        return new Account(UUID.fromString(rs.getString("uuid")), rs.getString("name"), Money.fromCents(rs.getLong("balance")),
                rs.getString("tier"));
    }

    /**
     * @param lowersBase true when money leaves the account: the interest base (lowest balance since the
     *                   last payout) follows the balance down. Money coming in never raises it, so it only
     *                   starts earning from the next payout.
     */
    private void writeBalance(Connection c, UUID uuid, BigDecimal balance, boolean lowersBase) throws SQLException {
        long cents = Money.toCents(balance);
        String sql = lowersBase
                ? "UPDATE " + p + "accounts SET balance = ?, updated_at = ?, "
                + "interest_base = CASE WHEN interest_base > ? THEN ? ELSE interest_base END WHERE uuid = ?"
                : "UPDATE " + p + "accounts SET balance = ?, updated_at = ? WHERE uuid = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, cents);
            ps.setLong(2, clock.getAsLong());
            if (lowersBase) {
                ps.setLong(3, cents);
                ps.setLong(4, cents);
                ps.setString(5, uuid.toString());
            } else {
                ps.setString(3, uuid.toString());
            }
            ps.executeUpdate();
        }
    }

    private void log(Connection c, UUID account, TransactionType type, BigDecimal amount, BigDecimal fee,
                     BigDecimal balanceAfter, @Nullable UUID other, @Nullable String otherName,
                     @Nullable String actor) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + p + "transactions (account, type, amount, fee, "
                + "balance_after, other_uuid, other_name, actor, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, account.toString());
            ps.setString(2, type.name());
            ps.setLong(3, Money.toCents(amount));
            ps.setLong(4, Money.toCents(fee));
            ps.setLong(5, Money.toCents(balanceAfter));
            if (other == null) ps.setNull(6, Types.CHAR); else ps.setString(6, other.toString());
            if (otherName == null) ps.setNull(7, Types.VARCHAR); else ps.setString(7, otherName);
            if (actor == null) ps.setNull(8, Types.VARCHAR); else ps.setString(8, actor);
            ps.setLong(9, clock.getAsLong());
            ps.executeUpdate();
        }
    }
}
