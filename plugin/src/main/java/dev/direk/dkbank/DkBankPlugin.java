package dev.direk.dkbank;

import dev.direk.dkbank.api.DkBankAPI;
import dev.direk.dkbank.bank.BankService;
import dev.direk.dkbank.command.BankCommand;
import dev.direk.dkbank.config.ConfigFile;
import dev.direk.dkbank.config.Messages;
import dev.direk.dkbank.config.Settings;
import dev.direk.dkbank.economy.Wallet;
import dev.direk.dkbank.interest.InterestService;
import dev.direk.dkbank.listener.AccountListener;
import dev.direk.dkbank.storage.BankStore;
import dev.direk.dkbank.storage.Database;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

import java.util.logging.Level;

/**
 * dkBank main class: loads the files, opens the database and wires everything together.
 */
public final class DkBankPlugin extends JavaPlugin implements DkBankAPI {

    private @Nullable Database database;
    private @Nullable BankService bank;
    private @Nullable InterestService interest;

    @Override
    public void onEnable() {
        String server = getServer().getName() + " " + getServer().getMinecraftVersion();
        getLogger().info("dkBank " + version() + " enabling on " + server + " (Java " + Runtime.version().feature() + ")");

        Settings settings = Settings.load(ConfigFile.load(this, "config.yml"), getLogger());
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

        bank = new BankService(this, store, new Wallet(), settings, messages, database.workerThreads());
        bank.start();
        getServer().getPluginManager().registerEvents(new AccountListener(this), this);

        // Also loads the accounts of players already online (e.g. after a reload).
        interest = new InterestService(this, store, bank);
        getServer().getPluginManager().registerEvents(interest, this);
        interest.start();

        BankCommand command = new BankCommand(this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(command.build(), "dkBank: your bank account", settings.aliases()));

        getServer().getServicesManager().register(DkBankAPI.class, this, this, ServicePriority.Normal);
    }

    @Override
    public void onDisable() {
        if (interest != null) interest.shutdown(); // pays everyone's unfinished interest cycle
        if (bank != null) bank.shutdown(); // finishes everything in progress, refunds included
        if (database != null) database.close();
        getServer().getServicesManager().unregisterAll(this);
    }

    /** Reloads config.yml and messages.yml. Storage and command alias changes need a restart. */
    public void reloadFiles() {
        Settings settings = Settings.load(ConfigFile.load(this, "config.yml"), getLogger());
        Messages messages = new Messages(ConfigFile.load(this, "messages.yml"));
        bank().reload(settings, messages);
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

    public InterestService interest() {
        if (interest == null) throw new IllegalStateException("dkBank isn't enabled");
        return interest;
    }

    @Override
    public String version() {
        return getPluginMeta().getVersion();
    }
}
