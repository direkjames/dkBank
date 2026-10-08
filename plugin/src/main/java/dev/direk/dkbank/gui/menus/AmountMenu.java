package dev.direk.dkbank.gui.menus;

import dev.direk.dkbank.bank.BankService;
import dev.direk.dkbank.gui.Menu;
import dev.direk.dkbank.gui.MenuLayout;
import dev.direk.dkbank.gui.MenuManager;
import dev.direk.dkbank.gui.MenuText.Values;
import dev.direk.dkbank.money.AmountInput;
import dev.direk.dkbank.money.Money;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Deposit or withdraw: preset amounts, "all"/"half" and a typed amount. Stays open, and the balance counts
 * to its new value after each one.
 * <p>
 * Settings: {@code presets} (amounts like 1k), {@code slots.presets}, templates {@code preset} and
 * {@code preset-unavailable} (shown when there isn't enough money). Actions: {@code amount:<amount>}
 * (e.g. amount:all) and {@code custom} (type an amount in chat).
 */
public final class AmountMenu extends Menu {

    private final boolean deposit;
    /** An amount is being moved; further clicks wait until it's done, so nothing moves twice. */
    private boolean pending;

    public AmountMenu(MenuManager menus, Player player, MenuLayout layout, boolean deposit) {
        super(menus, player, layout);
        this.deposit = deposit;
    }

    @Override
    protected void onOpen() {
        refreshBalance(true);
    }

    @Override
    protected Values values() {
        Map<String, String> v = common();
        BankService bank = menus.bank();
        v.put("fee", (deposit ? BigDecimal.ZERO : bank.settings().withdrawFeePercent()).stripTrailingZeros().toPlainString());
        v.put("min", bank.fmt(bank.settings().minAmount()));
        return new Values(v, richCommon());
    }

    @Override
    protected void draw() {
        List<String> amounts = layout.file().getStringList("presets");
        List<Integer> slots = layout.slots("presets");
        Values base = values();
        for (int i = 0; i < Math.min(amounts.size(), slots.size()); i++) {
            AmountInput input;
            try {
                input = AmountInput.parse(amounts.get(i));
            } catch (AmountInput.InvalidAmountException e) {
                menus.log().warning("menus/" + layout.name() + ".yml → presets: '" + amounts.get(i) + "' isn't an amount.");
                continue;
            }
            BigDecimal amount = input.resolve(available());
            Map<String, String> v = new HashMap<>(base.plain());
            v.put("amount", menus.bank().fmt(amount));
            boolean affordable = amount.signum() > 0 && amount.compareTo(available()) <= 0;
            ItemStack item = affordable ? null : template("preset-unavailable", new Values(v, base.rich()), null);
            if (item == null) item = template("preset", new Values(v, base.rich()), null);
            set(slots.get(i), item, type -> run(input));
        }
    }

    /** What the player can move: wallet money for a deposit, bank money for a withdrawal. */
    private BigDecimal available() {
        if (deposit) return menus.bank().wallet().available() ? menus.bank().wallet().balance(player) : Money.ZERO;
        return balance == null ? Money.ZERO : balance;
    }

    @Override
    protected boolean action(String name, String arg) {
        switch (name) {
            case "amount" -> {
                try {
                    run(AmountInput.parse(arg));
                } catch (AmountInput.InvalidAmountException e) {
                    menus.log().warning("menus/" + layout.name() + ".yml: 'amount:" + arg + "' isn't an amount.");
                }
                return true;
            }
            case "custom" -> {
                ask();
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private void run(AmountInput input) {
        if (pending) return;
        pending = true;
        BankService.Outcome done = success -> {
            pending = false;
            result(success);
            if (isOpen()) refreshBalance(true);
        };
        if (deposit) menus.bank().deposit(player, input, done);
        else menus.bank().withdraw(player, input, done);
    }

    private void ask() {
        java.math.BigDecimal before = balance;
        menus.prompts().ask(player, deposit ? "menu.input-deposit" : "menu.input-withdraw", Map.of(), text -> {
            AmountInput input;
            try {
                input = AmountInput.parse(text);
            } catch (AmountInput.InvalidAmountException e) {
                menus.bank().messages().send(player, "invalid-amount", Map.of("input", text));
                reopen();
                return;
            }
            BankService.Outcome done = success -> {
                if (player.isOnline()) menus.open(deposit ? "deposit" : "withdraw", player, before);
            };
            if (deposit) menus.bank().deposit(player, input, done);
            else menus.bank().withdraw(player, input, done);
        }, this::reopen);
    }

    private void reopen() {
        if (player.isOnline()) menus.open(deposit ? "deposit" : "withdraw", player);
    }
}
