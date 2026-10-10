package dev.direk.dkbank.startup;

import dev.direk.dkbank.config.Settings;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** The text the startup banner shows for storage and AFK detection. Author: direk james */
class StartupBannerTest {

    private static Settings.Storage storage(Settings.StorageType type) {
        return new Settings.Storage(type, "bank.db", "db.example.com", 3307, "economy",
                "user", "secret-password", false, 6, "dkbank_");
    }

    private static Settings.AfkDetection afk(boolean enabled, long idleMillis, List<String> placeholders, boolean plugins) {
        return new Settings.AfkDetection(enabled, idleMillis, placeholders, Set.of("yes", "true"), plugins);
    }

    @Test
    void describesStorageWithoutThePassword() {
        assertEquals("SQLite (bank.db)", StartupBanner.describe(storage(Settings.StorageType.SQLITE)));
        String mysql = StartupBanner.describe(storage(Settings.StorageType.MYSQL));
        assertEquals("MySQL (db.example.com:3307/economy)", mysql);
        assertFalse(mysql.contains("secret"), "the password must never be shown");
    }

    @Test
    void listsEveryWayOfDetectingAfk() {
        assertEquals("dkCore, idle 5m, 3 placeholders",
                StartupBanner.describeAfk(afk(true, 300_000, List.of("a", "b", "c"), true), List.of("dkCore"), true));
        assertEquals("idle 5m, 1 placeholder",
                StartupBanner.describeAfk(afk(true, 300_000, List.of("a"), true), List.of(), true));
    }

    @Test
    void skipsWhatIsTurnedOffOrMissing() {
        // AFK plugins turned off: dkCore isn't used even though it registered a source
        assertEquals("idle 5m",
                StartupBanner.describeAfk(afk(true, 300_000, List.of(), false), List.of("dkCore"), true));
        // Placeholders don't count without PlaceholderAPI
        assertEquals("dkCore",
                StartupBanner.describeAfk(afk(true, 0, List.of("a"), true), List.of("dkCore"), false));
        assertEquals("nothing set (see afk-detection in config.yml)",
                StartupBanner.describeAfk(afk(true, 0, List.of(), true), List.of(), false));
        assertEquals("off (AFK time earns the online rate)",
                StartupBanner.describeAfk(afk(false, 300_000, List.of("a"), true), List.of("dkCore"), true));
    }
}
