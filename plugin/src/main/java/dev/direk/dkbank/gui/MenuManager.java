package dev.direk.dkbank.gui;

import dev.direk.dkbank.bank.BankService;
import dev.direk.dkbank.bank.Leaderboard;
import dev.direk.dkbank.bank.TierService;
import dev.direk.dkbank.config.ConfigFile;
import dev.direk.dkbank.gui.menus.AmountMenu;
import dev.direk.dkbank.gui.menus.ConfirmUpgradeMenu;
import dev.direk.dkbank.gui.menus.HistoryMenu;
import dev.direk.dkbank.gui.menus.MainMenu;
import dev.direk.dkbank.gui.menus.TiersMenu;
import dev.direk.dkbank.gui.menus.TopMenu;
import dev.direk.dkbank.gui.menus.TransferMenu;
import dev.direk.dkbank.interest.InterestService;
import dev.direk.dkbank.tier.Tier;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Opens menus, loads their files and keeps menu items where they belong.
 */
public final class MenuManager implements Listener {

    /** Every menu file, in the menus folder. */
    public static final List<String> FILES = List.of("main", "deposit", "withdraw", "transfer", "tiers",
            "confirm-upgrade", "history", "top");
    /** Clicks closer together than this are ignored, so a double click can't run an action twice. */
    private static final long CLICK_COOLDOWN_MILLIS = 150;

    private final JavaPlugin plugin;
    private final BankService bank;
    private final TierService tiers;
    private final InterestService interest;
    private final Leaderboard leaderboard;
    private final ChatPrompts prompts;
    private final NamespacedKey key;
    private final Map<UUID, Long> lastClick = new HashMap<>();
    private final Set<String> warned = Collections.synchronizedSet(new HashSet<>());
    private Map<String, MenuLayout> layouts = Map.of();

    public MenuManager(JavaPlugin plugin, BankService bank, TierService tiers, InterestService interest,
                       Leaderboard leaderboard) {
        this.leaderboard = leaderboard;
        this.plugin = plugin;
        this.bank = bank;
        this.tiers = tiers;
        this.interest = interest;
        this.prompts = new ChatPrompts(plugin, bank);
        this.key = new NamespacedKey(plugin, "menu_item");
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getPluginManager().registerEvents(prompts, plugin);
    }

    /** Loads every menu file. A file with a mistake keeps its previous version (or the built-in one). */
    public void load() {
        Map<String, MenuLayout> loaded = new HashMap<>();
        for (String name : FILES) {
            String path = "menus/" + name + ".yml";
            YamlConfiguration file = ConfigFile.loadAsIs(plugin, path);
            if (file == null) {
                MenuLayout previous = layouts.get(name);
                if (previous != null) {
                    plugin.getLogger().severe("Kept the previous " + path + " until it's fixed.");
                    loaded.put(name, previous);
                    continue;
                }
                plugin.getLogger().severe("Using the built-in " + path + " until it's fixed.");
                file = builtIn(path);
            }
            loaded.put(name, new MenuLayout(name, file, plugin.getLogger()));
        }
        layouts = Map.copyOf(loaded);
    }

    private YamlConfiguration builtIn(String path) {
        try (var in = plugin.getResource(path)) {
            if (in == null) return new YamlConfiguration();
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            return new YamlConfiguration();
        }
    }

    // ------------------------------------------------------------------ opening

    /** Opens a menu by file name, checking its permission. Unknown names are logged. */
    public void open(String name, Player player) {
        open(name, player, null);
    }

    /**
     * @param shownBalance the balance the player saw before (e.g. before a payment); the menu counts from
     *                     it to the real balance. Null to just show the balance.
     */
    public void open(String name, Player player, @Nullable BigDecimal shownBalance) {
        if (!plugin.isEnabled() || !player.isOnline()) return; // e.g. a result arriving while the server stops
        Menu menu = switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "main" -> allowed(player, "dkbank.use") ? new MainMenu(this, player, layout("main")) : null;
            case "deposit" -> allowed(player, "dkbank.deposit") ? new AmountMenu(this, player, layout("deposit"), true) : null;
            case "withdraw" -> allowed(player, "dkbank.withdraw") ? new AmountMenu(this, player, layout("withdraw"), false) : null;
            case "tiers" -> allowed(player, "dkbank.tiers") ? new TiersMenu(this, player, layout("tiers")) : null;
            case "transfer" -> {
                openTransfer(player, 1);
                yield null;
            }
            case "history" -> {
                openHistory(player, 1);
                yield null;
            }
            case "top" -> {
                openTop(player, 1);
                yield null;
            }
            default -> {
                unknownAction(null, "open:" + name);
                yield null;
            }
        };
        if (menu != null) menu.showing(shownBalance).open();
    }

    public void openTransfer(Player player, int page) {
        if (!plugin.isEnabled() || !player.isOnline()) return;
        if (!allowed(player, "dkbank.pay")) return;
        if (!bank.settings().transfersEnabled()) {
            bank.messages().send(player, "transfers-disabled");
            return;
        }
        new TransferMenu(this, player, layout("transfer"), page).open();
    }

    public void openHistory(Player player, int page) {
        if (!plugin.isEnabled() || !player.isOnline()) return;
        if (allowed(player, "dkbank.history")) new HistoryMenu(this, player, layout("history"), page).open();
    }

    public void openTop(Player player, int page) {
        if (!plugin.isEnabled() || !player.isOnline()) return;
        if (allowed(player, "dkbank.top")) {
            new TopMenu(this, player, layout("top"), leaderboard.snapshot(), page).open();
        }
    }

    public void openConfirmUpgrade(Player player, Tier tier) {
        if (!plugin.isEnabled() || !player.isOnline()) return;
        if (allowed(player, "dkbank.upgrade")) new ConfirmUpgradeMenu(this, player, layout("confirm-upgrade"), tier).open();
    }

    private boolean allowed(Player player, String permission) {
        if (player.hasPermission(permission)) return true;
        bank.messages().send(player, "menu.no-permission");
        return false;
    }

    private MenuLayout layout(String name) {
        MenuLayout layout = layouts.get(name);
        if (layout == null) throw new IllegalStateException("Menu " + name + " isn't loaded");
        return layout;
    }

    /** Closes every open dkBank menu (when the plugin stops, so menu items can't be taken). */
    public void closeAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof Menu) player.closeInventory();
        }
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.LOW)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top.getHolder(false) instanceof Menu menu) {
            event.setCancelled(true); // nothing moves in or out of a menu, including shift-clicks and number keys
            if (event.getRawSlot() < 0 || event.getRawSlot() >= top.getSize()
                    || !(event.getWhoClicked() instanceof Player player)) return; // a click in their own inventory
            if (event.getClick() == ClickType.DOUBLE_CLICK) return; // the second half of a double click
            long now = System.currentTimeMillis();
            Long last = lastClick.put(player.getUniqueId(), now);
            if (last != null && now - last < CLICK_COOLDOWN_MILLIS) return;
            int slot = event.getRawSlot();
            ClickType type = event.getClick();
            // Opening or closing inventories inside a click event isn't allowed, so act on the next tick.
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (menu.isOpen()) menu.click(slot, type);
            });
            return;
        }
        // A menu item outside a menu (should never happen): remove it.
        if (isMenuItem(event.getCurrentItem())) {
            event.setCancelled(true);
            event.setCurrentItem(null);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof Menu) event.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastClick.remove(event.getPlayer().getUniqueId());
        prompts.cancel(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ helpers for menus

    /** Marks an item as a menu item. */
    public @Nullable ItemStack tag(@Nullable ItemStack item) {
        if (item == null) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || meta.getPersistentDataContainer().has(key)) return item;
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isMenuItem(@Nullable ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(key);
    }

    public void sound(Player player, MenuLayout layout, String which) {
        Key sound = layout.sound(which);
        if (sound != null) player.playSound(Sound.sound(sound, Sound.Source.MASTER, 0.8f, 1f));
    }

    void unknownAction(@Nullable MenuLayout layout, String action) {
        String where = layout == null ? "a menu file" : "menus/" + layout.name() + ".yml";
        if (warned.add(where + action)) plugin.getLogger().warning("Unknown action '" + action + "' in " + where + ".");
    }

    public JavaPlugin plugin() {
        return plugin;
    }

    public BankService bank() {
        return bank;
    }

    public TierService tierService() {
        return tiers;
    }

    public InterestService interest() {
        return interest;
    }

    public ChatPrompts prompts() {
        return prompts;
    }

    public Logger log() {
        return plugin.getLogger();
    }
}
