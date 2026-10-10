package dev.direk.dkbank;

import dev.direk.dkbank.api.DkBankAPI;
import dev.direk.dkbank.bank.BankApiImpl;
import dev.direk.dkbank.bank.BankService;
import dev.direk.dkbank.bank.Leaderboard;
import dev.direk.dkbank.bank.ReportService;
import dev.direk.dkbank.bank.TierService;
import dev.direk.dkbank.command.BankCommand;
import dev.direk.dkbank.config.ConfigFile;
import dev.direk.dkbank.config.Messages;
import dev.direk.dkbank.config.Settings;
import dev.direk.dkbank.economy.Wallet;
import dev.direk.dkbank.gui.MenuManager;
import dev.direk.dkbank.interest.AfkDetector;
import dev.direk.dkbank.interest.InterestService;
import dev.direk.dkbank.listener.AccountListener;
import dev.direk.dkbank.startup.StartupBanner;
import dev.direk.dkbank.storage.BankStore;
import dev.direk.dkbank.storage.Database;
import dev.direk.dkbank.tier.Tier;
import dev.direk.dkbank.tier.Tiers;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * dkBank main class: loads the files, opens the database and wires everything together.
 * Author: direk james
 */
public final class DkBankPlugin extends JavaPlugin {

    private @Nullable Database database;
    private @Nullable BankService bank;
    private @Nullable InterestService interest;
    private @Nullable TierService tierService;
    private @Nullable MenuManager menus;
    private @Nullable Leaderboard leaderboard;
    private @Nullable ReportService reports;
    private final dev.direk.dkbank.util.UpdateChecker updates = new dev.direk.dkbank.util.UpdateChecker(this);
    private final StartupBanner banner = new StartupBanner(this);
    private boolean papi;

    /** Settings that moved out of config.yml, and where to. */
    private static final Map<String, String> MOVED = Map.of(
            "limits.max-balance", "max-balance of each tier in tiers.yml",
            "interest.online.rate", "online-rate of each tier in tiers.yml",
            "interest.offline.rate", "offline-rate of each tier in tiers.yml",
            "interest.cap", "the interest cap of each tier in tiers.yml",
            "interest.max-per-payout", "max-per-payout of each tier in tiers.yml");

    @Override
    public void onEnable() {
        long start = System.currentTimeMillis();

        Settings settings;
        Messages messages;
        try {
            settings = Settings.load(ConfigFile.load(this, "config.yml", MOVED), getLogger());
            messages = new Messages(ConfigFile.load(this, "messages.yml"));
        } catch (ConfigFile.BrokenFileException e) {
            getLogger().severe(e.getMessage());
            banner.printFailed(e.file() + " has a mistake",
                    "dkBank won't start with wrong settings (e.g. the wrong database)",
                    "Fix " + e.file() + " (https://yamlchecker.com helps) and restart");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        banner.enabled(settings.startupBanner());

        BankStore store;
        long storageStart = System.currentTimeMillis();
        try {
            database = Database.open(settings.storage(), getDataFolder());
            store = new BankStore(database.dataSource(), database.dialect(), settings.storage().tablePrefix(),
                    System::currentTimeMillis);
            store.createSchema();
        } catch (RuntimeException e) {
            getLogger().log(Level.SEVERE, "Couldn't open the " + settings.storage().type().name().toLowerCase()
                    + " database. Check the storage section of config.yml. dkBank is disabled.", e);
            if (database != null) database.close();
            database = null;
            banner.printFailed("Couldn't open " + StartupBanner.describe(settings.storage()),
                    String.valueOf(e.getMessage()), "Check the storage section of config.yml and restart");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        long storageMs = System.currentTimeMillis() - storageStart;

        Tiers tiers = loadTiers(settings);
        if (tiers == null) tiers = Tiers.parse(Map.of(), settings.interest().template(), getLogger());

        bank = new BankService(this, store, new Wallet(), settings, messages, tiers, database.workerThreads());
        tierService = new TierService(bank);
        bank.start();
        getServer().getPluginManager().registerEvents(new AccountListener(this), this);

        // Also loads the accounts of players already online (e.g. after a reload).
        interest = new InterestService(this, store, bank);
        getServer().getPluginManager().registerEvents(interest, this);
        interest.start();

        // Logins older than the alt limit remembers are of no use: forget them.
        long forgetBefore = System.currentTimeMillis() - settings.alts().windowMillis();
        bank.query("forget old login addresses", () -> store.pruneIps(forgetBefore), removed -> {
            if (removed > 0) getLogger().info("Forgot " + removed + " old login addresses.");
        });

        leaderboard = new Leaderboard(this, store, () -> bank().settings());
        leaderboard.start();
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                papi = dev.direk.dkbank.hook.PapiHook.register(this);
            } catch (LinkageError | RuntimeException e) {
                getLogger().log(Level.WARNING, "Couldn't register the PlaceholderAPI placeholders", e);
            }
        }

        menus = new MenuManager(this, bank, tierService, interest, leaderboard);
        reports = new ReportService(bank, leaderboard);
        menus.load();
        menus.register();

        BankCommand command = new BankCommand(this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(command.build(), "dkBank: your bank account", settings.aliases()));

        try {
            dev.direk.dkbank.hook.Metrics.start(this);
        } catch (RuntimeException | LinkageError e) {
            getLogger().log(Level.FINE, "bStats didn't start", e);
        }
        if (settings.updateChecker()) {
            updates.check(newer -> getLogger().info("dkBank " + newer + " is out (you have " + version()
                    + "). Download it where you bought dkBank."));
        }

        getServer().getServicesManager().register(DkBankAPI.class, new BankApiImpl(version(), bank, leaderboard), this,
                ServicePriority.Normal);

        banner.printStartup(StartupBanner.describe(settings.storage()), storageMs,
                tiers.all().stream().map(Tier::id).toList(), papi, System.currentTimeMillis() - start);
    }

    @Override
    public void onDisable() {
        if (menus != null) menus.closeAll(); // menu items must never stay in a player's hands
        if (leaderboard != null) leaderboard.stop();
        if (interest != null) interest.shutdown(); // pays everyone's unfinished interest cycle
        if (bank != null) bank.shutdown(); // finishes everything in progress, refunds included
        if (database != null) database.close();
        getServer().getServicesManager().unregisterAll(this);
        if (bank != null) banner.printShutdown(); // only if dkBank had started
    }

    /** Reloads config.yml and messages.yml. Storage and command alias changes need a restart. */
    /**
     * @return null if everything reloaded, or the name of a file with a mistake (nothing was changed then)
     */
    public @Nullable String reloadFiles() {
        Settings settings;
        Messages messages;
        try {
            settings = Settings.load(ConfigFile.load(this, "config.yml", MOVED), getLogger());
            messages = new Messages(ConfigFile.load(this, "messages.yml"));
        } catch (ConfigFile.BrokenFileException e) {
            getLogger().severe(e.getMessage() + " Kept the previous settings.");
            return e.file();
        }
        banner.enabled(settings.startupBanner());
        Tiers tiers = loadTiers(settings);
        if (tiers == null) {
            getLogger().severe("Kept the previous tiers until tiers.yml is fixed.");
            tiers = bank().tiers();
        }
        bank().reload(settings, messages, tiers);
        menus().load();
        leaderboard().start(); // the refresh time may have changed
        // The alt limit may have been turned on or changed: decide again for everyone online.
        for (org.bukkit.entity.Player player : getServer().getOnlinePlayers()) {
            java.util.UUID uuid = player.getUniqueId();
            bank().query("check alt accounts", () -> {
                bank().alts().recheck(uuid);
                return true;
            }, done -> {
            });
        }
        return null;
    }

    /** Reads tiers.yml and registers each tier's permission. Null if the file has a mistake. */
    private @Nullable Tiers loadTiers(Settings settings) {
        YamlConfiguration file = ConfigFile.loadAsIs(this, "tiers.yml");
        if (file == null) return null;
        ConfigurationSection section = file.getConfigurationSection("tiers");
        Tiers tiers = Tiers.parse(section == null ? Map.of() : ConfigFile.toMap(section),
                settings.interest().template(), getLogger());
        PluginManager pm = getServer().getPluginManager();
        for (Tier tier : tiers.all()) {
            // Registered as "default false": without this, unknown permissions count as given to ops,
            // and every op would get the highest tier.
            if (pm.getPermission(tier.permission()) == null) {
                pm.addPermission(new Permission(tier.permission(), "dkBank tier " + tier.id(), PermissionDefault.FALSE));
            }
        }
        return tiers;
    }

    /**
     * Once the server has loaded: shows which economy dkBank found (or what's missing), what decides who is
     * AFK and whether dkBank is linked to dkCore. Those plugins can enable after dkBank, so it waits until now.
     */
    public void reportEconomy() {
        if (bank == null) return;
        Wallet wallet = bank.wallet();
        Settings settings = bank.settings();
        String afk = StartupBanner.describeAfk(settings.afk(), List.copyOf(AfkDetector.sourcePlugins()),
                getServer().getPluginManager().isPluginEnabled("PlaceholderAPI"));
        banner.printReady(wallet.vaultInstalled(), wallet.available() ? wallet.providerName() : null, afk);
    }

    public BankService bank() {
        if (bank == null) throw new IllegalStateException("dkBank isn't enabled");
        return bank;
    }

    /** /bank admin info: everything support needs to know about this server's setup. */
    public void sendInfo(org.bukkit.command.CommandSender sender) {
        BankService b = bank();
        Leaderboard.Snapshot top = leaderboard().snapshot();
        String newer = updates.newerVersion();
        java.util.Map<String, String> v = new java.util.HashMap<>();
        v.put("version", version());
        v.put("server", getServer().getName() + " " + getServer().getMinecraftVersion());
        v.put("java", String.valueOf(Runtime.version().feature()));
        v.put("storage", b.settings().storage().type().name().toLowerCase(java.util.Locale.ROOT));
        v.put("economy", b.wallet().providerName());
        v.put("papi", String.valueOf(getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")));
        v.put("accounts", String.valueOf(top.totals().accounts()));
        v.put("total", b.fmt(top.totals().balance()));
        v.put("loaded", String.valueOf(getServer().getOnlinePlayers().size()));
        v.put("tiers", String.valueOf(b.tiers().all().size()));
        v.put("alts", b.settings().alts().enabled() ? "max " + b.settings().alts().maxPerAddress() : "off");
        v.put("menus", String.valueOf(b.settings().menus().openFromCommands()));
        v.put("latest", newer == null ? "you're up to date (or not checked)" : newer);
        b.messages().send(sender, "admin.info", v);
    }

    public dev.direk.dkbank.util.UpdateChecker updates() {
        return updates;
    }

    public ReportService reports() {
        if (reports == null) throw new IllegalStateException("dkBank isn't enabled");
        return reports;
    }

    public Leaderboard leaderboard() {
        if (leaderboard == null) throw new IllegalStateException("dkBank isn't enabled");
        return leaderboard;
    }

    public MenuManager menus() {
        if (menus == null) throw new IllegalStateException("dkBank isn't enabled");
        return menus;
    }

    public TierService tiers() {
        if (tierService == null) throw new IllegalStateException("dkBank isn't enabled");
        return tierService;
    }

    public InterestService interest() {
        if (interest == null) throw new IllegalStateException("dkBank isn't enabled");
        return interest;
    }

    /** The running dkBank version, e.g. 1.0.0. */
    public String version() {
        return getPluginMeta().getVersion();
    }
}
