package dev.direk.dkbank.bank;

import dev.direk.dkbank.config.Settings;
import dev.direk.dkbank.storage.BankStore;
import dev.direk.dkbank.storage.StoreTypes.AltAccount;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The alt-account limit: only the first {@code max} accounts that played from one internet connection can
 * use the bank. Later ones are locked (no deposits, transfers, upgrades or interest) unless staff allow
 * them or they have {@code dkbank.alts.bypass}.
 * <p>
 * Addresses are stored as salted SHA-256 hashes, never as the address itself. Local addresses (127.0.0.1,
 * 192.168.x.x, ...) are ignored: they mean a proxy without IP forwarding, or a LAN, where everyone would
 * look like one person.
 */
public final class AltGuard {

    public static final String BYPASS = "dkbank.alts.bypass";

    private final BankStore store;
    private final Supplier<Settings> settings;
    private final Logger log;
    private final Set<UUID> locked = ConcurrentHashMap.newKeySet();
    private volatile @Nullable String salt;
    private volatile boolean warnedLocal;

    public AltGuard(BankStore store, Supplier<Settings> settings, Logger log) {
        this.store = store;
        this.settings = settings;
        this.log = log;
    }

    /** At login (on the login thread, after the account exists): records the address and decides. */
    public void check(UUID uuid, @Nullable InetAddress address) {
        Settings.Alts s = settings.get().alts();
        if (!s.enabled() || address == null) {
            locked.remove(uuid);
            return;
        }
        if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                || address.isAnyLocalAddress()) {
            if (!warnedLocal) {
                warnedLocal = true;
                log.warning("Players are joining from a local address (" + address.getHostAddress() + "), so the alt-account "
                        + "limit can't tell them apart and ignores them. Behind a proxy (Velocity, BungeeCord), turn on "
                        + "IP forwarding so the server sees real addresses.");
            }
            locked.remove(uuid);
            return;
        }
        try {
            String hash = hash(address.getHostAddress());
            long since = System.currentTimeMillis() - s.windowMillis();
            List<AltAccount> accounts = store.recordLogin(uuid, hash, since);
            if (AltRule.overLimit(accounts, uuid, s.maxPerAddress())) locked.add(uuid);
            else locked.remove(uuid);
        } catch (RuntimeException e) {
            log.log(Level.SEVERE, "Couldn't check alt accounts for " + uuid + "; letting them use the bank", e);
            locked.remove(uuid);
        }
    }

    /** Decides again from the account's most recent address (after staff change an exemption). Worker thread. */
    public void recheck(UUID uuid) {
        Settings.Alts s = settings.get().alts();
        if (!s.enabled()) {
            locked.remove(uuid);
            return;
        }
        String hash = store.latestIp(uuid).orElse(null);
        if (hash == null) {
            locked.remove(uuid);
            return;
        }
        List<AltAccount> accounts = store.accountsOnIp(hash, System.currentTimeMillis() - s.windowMillis());
        if (AltRule.overLimit(accounts, uuid, s.maxPerAddress())) locked.add(uuid);
        else locked.remove(uuid);
    }

    /** Whether the player's bank is locked right now. Main thread (checks the bypass permission). */
    public boolean isLocked(Player player) {
        boolean bypass = player.hasPermission(BYPASS);
        if (bypass) bypassing.add(player.getUniqueId());
        else bypassing.remove(player.getUniqueId());
        return isLocked(player.getUniqueId(), bypass);
    }

    /** Players with the bypass permission, as last checked on the main thread (for other threads). */
    private final Set<UUID> bypassing = ConcurrentHashMap.newKeySet();

    /** Like {@link #isLocked(Player)} from any thread, using the bypass permission as last checked. */
    public boolean isLockedCached(UUID uuid) {
        return isLocked(uuid, bypassing.contains(uuid));
    }

    /** Like {@link #isLocked(Player)}, with the bypass permission already checked. Any thread. */
    public boolean isLocked(UUID uuid, boolean bypass) {
        return settings.get().alts().enabled() && locked.contains(uuid) && !bypass;
    }

    public void forget(UUID uuid) {
        locked.remove(uuid);
        bypassing.remove(uuid);
    }

    public void clear() {
        locked.clear();
    }

    /** Salted SHA-256 of an address. */
    public String hash(String address) {
        String s = salt;
        if (s == null) salt = s = store.ipSalt();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((s + "|" + address).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this Java", e);
        }
    }
}
