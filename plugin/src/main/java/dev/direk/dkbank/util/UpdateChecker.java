package dev.direk.dkbank.util;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Asks SpigotMC which dkBank version is the newest, in the background. Never blocks the server, and a
 * failure (no internet, site down) is only logged quietly.
 */
public final class UpdateChecker {

    /** SpigotMC resource id of dkBank. 0 until it's published there: no checks. */
    public static final int SPIGOT_RESOURCE_ID = 0;

    private final JavaPlugin plugin;
    private volatile @Nullable String latest;

    public UpdateChecker(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** @return the newer version found, or null if none (or not checked yet) */
    public @Nullable String newerVersion() {
        String l = latest;
        return l != null && Versions.isNewer(l, plugin.getPluginMeta().getVersion()) ? l : null;
    }

    /** Checks in the background; {@code found} runs on the main thread if a newer version exists. */
    public void check(Consumer<String> found) {
        if (SPIGOT_RESOURCE_ID <= 0) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                HttpRequest request = HttpRequest.newBuilder(URI.create(
                                "https://api.spigotmc.org/legacy/update.php?resource=" + SPIGOT_RESOURCE_ID))
                        .timeout(Duration.ofSeconds(10)).header("User-Agent", "dkBank").GET().build();
                String body = client.send(request, HttpResponse.BodyHandlers.ofString()).body().trim();
                if (body.isEmpty() || body.length() > 32) return;
                latest = body;
                String newer = newerVersion();
                if (newer != null && plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, () -> found.accept(newer));
            } catch (Exception e) {
                plugin.getLogger().log(Level.FINE, "Update check failed", e);
            }
        });
    }
}
