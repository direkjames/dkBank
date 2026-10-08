package dev.direk.dkbank;

import dev.direk.dkbank.api.DkBankAPI;
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
import dev.direk.dkbank.interest.InterestService;
import dev.direk.dkbank.listener.AccountListener;
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

import java.util.Map;
import java.util.logging.Level;

/**
 * dkBank main class: loads the files, opens the database and wires everything together.
 */
public final class DkBankPlugin extends JavaPlugin implements DkBankAPI {

    private @Nullable Database database;
    private @Nullable BankService bank;
    private @Nullable InterestService interest;
    private @Nullable TierService tierService;
    private @Nullable MenuManager menus;
    private @Nullable Leaderboard leaderboard;
    private @Nullable ReportService reports;

    /** Settings that moved out of config.yml, and where to. */
    private static final Map<String, String> MOVED = Map.of(
            "limits.max-balance", "max-balance of each tier in tiers.yml",
            "interest.online.rate", "online-rate of each tier in tiers.yml",
            "interest.offline.rate", "offline-rate of each tier in tiers.yml",
            "interest.cap", "the interest cap of each tier in tiers.yml",
            "interest.max-per-payout", "max-per-payout of each tier in tiers.yml");

    @Override
    public void onEnable() {
        String server = getServer().getName() + " " + getServer().getMinecraftVersion();
        getLogger().info("dkBank " + version() + " enabling on " + server + " (Java " + Runtime.version().feature() + ")");

        Settings settings = Settings.load(ConfigFile.load(this, "config.yml", MOVED), getLogger());
        Messages messages = new Messages(ConfigFile.load(this, "messages.yml"));

        BankStore store;
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
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getLogger().info("Storage: " + settings.storage().type().name().toLowerCase());

        Tiers tiers = loadTiers(settings);
        if (tiers == null) tiers = Tiers.parse(Map.of(), settings.interest().template(), getLogger());
        getLogger().info("Bank tiers: " + String.join(", ", tiers.all().stream().map(Tier::id).toList()));

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
                if (dev.direk.dkbank.hook.PapiHook.register(this)) getLogger().info("PlaceholderAPI: %dkbank_...% placeholders ready");
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

        getServer().getServicesManager().register(DkBankAPI.class, this, this, ServicePriority.Normal);
    }

    @Override
    public void onDisable() {
        if (menus != null) menus.closeAll(); // menu items must never stay in a player's hands
        if (leaderboard != null) leaderboard.stop();
        if (interest != null) interest.shutdown(); // pays everyone's unfinished interest cycle
        if (bank != null) bank.shutdown(); // finishes everything in progress, refunds included
        if (database != null) database.close();
        getServer().getServicesManager().unregisterAll(this);
    }

    /** Reloads config.yml and messages.yml. Storage and command alias changes need a restart. */
    public void reloadFiles() {
        Settings settings = Settings.load(ConfigFile.load(this, "config.yml", MOVED), getLogger());
        Messages messages = new Messages(ConfigFile.load(this, "messages.yml"));
        Tiers tiers = loadTiers(settings);
        if (tiers == null) {
            getLogger().severe("Kept the previous tiers until tiers.yml is fixed.");
            tiers = bank().tiers();
        }
        bank().reload(settings, messages, tiers);
        menus().load();
        leaderboard().start(); // the refresh time may have changed
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

    /** Logs which economy dkBank found, or what's missing. */
    public void reportEconomy() {
        if (bank == null) return;
        Wallet wallet = bank.wallet();
        if (!wallet.vaultInstalled()) {
            getLogger().severe("Vault isn't installed. dkBank needs Vault (or VaultUnlocked) to move money between wallets and banks.");
        } else if (!wallet.available()) {
            getLogger().severe("Vault is installed but no economy plugin is connected to it (e.g. EssentialsX or CMI).");
        } else {
            getLogger().info("Economy: " + wallet.providerName() + " (through Vault)");
        }
    }

    public BankService bank() {
        if (bank == null) throw new IllegalStateException("dkBank isn't enabled");
        return bank;
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

    @Override
    public String version() {
        return getPluginMeta().getVersion();
    }
}
