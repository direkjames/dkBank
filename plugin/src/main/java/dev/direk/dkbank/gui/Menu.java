package dev.direk.dkbank.gui;

import dev.direk.dkbank.bank.BankService;
import dev.direk.dkbank.gui.MenuText.Values;
import dev.direk.dkbank.money.Money;
import dev.direk.dkbank.storage.StoreTypes.Account;
import dev.direk.dkbank.tier.Tier;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * An open menu. Draws the layout's fill, border and fixed items, then whatever the menu adds itself, and
 * runs the actions of clicked items. Main thread only.
 * <p>
 * Actions every menu understands: {@code close}, {@code back} (to the main menu), {@code open:<menu>}
 * and {@code command:<command>} (run by the player, {@code <player>} is their name).
 */
public abstract class Menu implements InventoryHolder {

    @FunctionalInterface
    protected interface Click {
        void run(ClickType type);
    }

    private static final int ANIMATION_FRAMES = 8;

    protected final MenuManager menus;
    protected final Player player;
    protected final MenuLayout layout;
    private @Nullable Inventory inventory;
    private final Map<Integer, Click> clicks = new HashMap<>();
    private ItemStack @Nullable [] contents;
    private int animation;
    /** The bank balance shown, which may be part-way through an animation. Null until loaded. */
    protected @Nullable BigDecimal balance;

    protected Menu(MenuManager menus, Player player, MenuLayout layout) {
        this.menus = menus;
        this.player = player;
        this.layout = layout;
        this.balance = menus.bank().cachedBalance(player.getUniqueId());
    }

    @Override
    public Inventory getInventory() {
        if (inventory == null) inventory = Bukkit.createInventory(this, layout.size()); // asked before opening
        return inventory;
    }

    // ------------------------------------------------------------------ opening and drawing

    /** Starts the balance display at {@code shown} (the menu then counts to the real balance). */
    public final Menu showing(@Nullable BigDecimal shown) {
        if (shown != null) balance = shown;
        return this;
    }

    public final void open() {
        menus.prompts().cancel(player.getUniqueId());
        Component title = MenuText.render(layout.title(), player, values());
        inventory = Bukkit.createInventory(this, layout.size(), title);
        render();
        player.openInventory(inventory);
        menus.sound(player, layout, "open");
        onOpen();
    }

    /** Called right after opening, e.g. to load data. */
    protected void onOpen() {
    }

    /** Draws everything again with the current values. */
    public final void render() {
        if (inventory == null) return;
        clicks.clear();
        contents = new ItemStack[layout.size()];
        Values values = values();
        ItemSpec.Context ctx = new ItemSpec.Context(player, values, null);

        ItemStack fill = layout.fill() == null ? null : menus.tag(layout.fill().build(ctx, menus.log()));
        ItemStack border = layout.border() == null ? null : menus.tag(layout.border().build(ctx, menus.log()));
        for (int slot = 0; slot < contents.length; slot++) {
            contents[slot] = border != null && layout.isBorder(slot) ? border : fill;
        }
        for (MenuLayout.Entry entry : layout.items()) {
            if (entry.permission() != null && !player.hasPermission(entry.permission())) continue;
            if (!visible(entry)) continue;
            ItemStack item = menus.tag(entry.item().build(ctx, menus.log()));
            for (int slot : entry.slots()) {
                contents[slot] = item;
                if (!entry.actions().isEmpty()) clicks.put(slot, type -> entry.actions().forEach(this::runAction));
            }
        }
        draw();
        inventory.setContents(contents);
    }

    /** Lets a menu hide a fixed item, e.g. "next page" on the last page. */
    protected boolean visible(MenuLayout.Entry entry) {
        return true;
    }

    /** Adds the menu's own items with {@link #set}. */
    protected void draw() {
    }

    /** Puts an item in a slot, with what happens when it's clicked (or null for nothing). */
    protected final void set(int slot, @Nullable ItemStack item, @Nullable Click click) {
        if (contents == null || slot < 0 || slot >= contents.length) return;
        contents[slot] = menus.tag(item);
        if (click != null) clicks.put(slot, click);
        else clicks.remove(slot);
    }

    /** Builds a template from the layout, or null if the file doesn't have it. */
    protected final @Nullable ItemStack template(String key, Values values, @Nullable Player target) {
        ItemSpec spec = layout.template(key);
        return spec == null ? null : spec.build(new ItemSpec.Context(player, values, target), menus.log());
    }

    public final boolean isOpen() {
        return inventory != null && player.isOnline()
                && player.getOpenInventory().getTopInventory().getHolder(false) == this;
    }

    // ------------------------------------------------------------------ values

    /** Values for this menu's texts. Menus add their own on top of {@link #common()}. */
    protected Values values() {
        return new Values(common(), richCommon());
    }

    /** Values every menu can use: balance, wallet, tier, limits and rates. */
    protected final Map<String, String> common() {
        BankService bank = menus.bank();
        Tier tier = bank.tierOf(player);
        Map<String, String> v = new HashMap<>(menus.tierService().details(tier));
        v.put("player", player.getName());
        v.put("balance", balance == null ? "…" : bank.fmt(balance));
        v.put("wallet", bank.wallet().available() ? bank.fmt(bank.wallet().balance(player)) : "-");
        v.put("tier-icon", tier.icon());
        v.put("room", tier.maxBalance() == null || balance == null ? v.get("max-balance")
                : bank.fmt(tier.maxBalance().subtract(balance).max(Money.ZERO)));
        return v;
    }

    protected final Map<String, Component> richCommon() {
        Map<String, Component> rich = new HashMap<>();
        rich.put("tier", MenuText.trusted(menus.bank().tierOf(player).displayName()));
        return rich;
    }

    // ------------------------------------------------------------------ balance

    /** Loads the bank balance; if it changed and {@code animate} is set, it counts up or down to it. */
    protected final void refreshBalance(boolean animate) {
        UUID uuid = player.getUniqueId();
        menus.bank().query("load a bank balance", () -> menus.bank().store().account(uuid).map(Account::balance), loaded -> {
            if (loaded.isEmpty() || !isOpen()) return;
            menus.bank().cacheBalance(uuid, loaded.get());
            BigDecimal from = balance;
            if (animate && from != null && from.compareTo(loaded.get()) != 0) {
                animateBalance(from, loaded.get());
            } else {
                balance = loaded.get();
                render();
            }
        });
    }

    private void animateBalance(BigDecimal from, BigDecimal to) {
        int id = ++animation;
        int[] frame = {0};
        Bukkit.getScheduler().runTaskTimer(menus.plugin(), task -> {
            if (id != animation || !isOpen()) {
                task.cancel();
                return;
            }
            frame[0]++;
            BigDecimal step = to.subtract(from).multiply(BigDecimal.valueOf(frame[0]))
                    .divide(BigDecimal.valueOf(ANIMATION_FRAMES), Money.SCALE, RoundingMode.DOWN);
            balance = frame[0] >= ANIMATION_FRAMES ? to : from.add(step);
            render();
            if (frame[0] >= ANIMATION_FRAMES) task.cancel();
        }, 0L, 2L);
    }

    // ------------------------------------------------------------------ clicks

    final void click(int slot, ClickType type) {
        Click click = clicks.get(slot);
        if (click == null) return;
        menus.sound(player, layout, "click");
        click.run(type);
    }

    private void runAction(String raw) {
        String action = raw.trim();
        int colon = action.indexOf(':');
        String name = (colon < 0 ? action : action.substring(0, colon)).trim().toLowerCase(java.util.Locale.ROOT);
        String arg = colon < 0 ? "" : action.substring(colon + 1).trim();
        switch (name) {
            case "close" -> player.closeInventory();
            case "back" -> back();
            case "open" -> menus.open(arg, player);
            case "command" -> {
                player.closeInventory();
                player.performCommand(arg.replace("<player>", player.getName()).replaceFirst("^/", ""));
            }
            default -> {
                if (!action(name, arg)) menus.unknownAction(layout, action);
            }
        }
    }

    /** Where {@code back} goes. */
    protected void back() {
        menus.open("main", player);
    }

    /** Menu-specific actions. @return false if the action isn't known */
    protected boolean action(String name, String arg) {
        return false;
    }

    /** Plays the success or error sound of this menu. */
    protected final void result(boolean success) {
        menus.sound(player, layout, success ? "success" : "error");
    }
}
