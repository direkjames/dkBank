package dev.direk.dkbank.gui.menus;

import dev.direk.dkbank.bank.BankService;
import dev.direk.dkbank.gui.Menu;
import dev.direk.dkbank.gui.MenuLayout;
import dev.direk.dkbank.gui.MenuManager;
import dev.direk.dkbank.gui.MenuText.Values;
import dev.direk.dkbank.money.AmountInput;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Send money to another player's bank: online players' heads (paged), or type any name.
 * <p>
 * Settings: {@code slots.players}, template {@code player} ({@code <target>} is their name).
 * Actions: {@code previous}, {@code next}, {@code type-name}. Fixed items named {@code previous} and
 * {@code next} only show when there's a page to go to.
 */
public final class TransferMenu extends Menu {

    private final int page;
    private final List<Player> targets;

    public TransferMenu(MenuManager menus, Player player, MenuLayout layout, int page) {
        super(menus, player, layout);
        this.targets = Bukkit.getOnlinePlayers().stream()
                .filter(p -> !p.equals(player) && player.canSee(p))
                .sorted(Comparator.comparing(p -> p.getName().toLowerCase(java.util.Locale.ROOT)))
                .map(p -> (Player) p)
                .toList();
        int perPage = Math.max(1, layout.slots("players").size());
        int pages = Math.max(1, (targets.size() + perPage - 1) / perPage);
        this.page = Math.max(1, Math.min(page, pages));
    }

    private int pages() {
        int perPage = Math.max(1, layout.slots("players").size());
        return Math.max(1, (targets.size() + perPage - 1) / perPage);
    }

    @Override
    protected Values values() {
        Map<String, String> v = common();
        v.put("page", String.valueOf(page));
        v.put("pages", String.valueOf(pages()));
        v.put("online", String.valueOf(targets.size()));
        v.put("fee", menus.bank().settings().transferFeePercent().stripTrailingZeros().toPlainString());
        return new Values(v, richCommon());
    }

    @Override
    protected boolean visible(MenuLayout.Entry entry) {
        if (entry.key().equals("previous")) return page > 1;
        if (entry.key().equals("next")) return page < pages();
        if (entry.key().equals("nobody-online")) return targets.isEmpty();
        return true;
    }

    @Override
    protected void draw() {
        List<Integer> slots = layout.slots("players");
        Values base = values();
        int start = (page - 1) * slots.size();
        for (int i = 0; i < slots.size() && start + i < targets.size(); i++) {
            Player target = targets.get(start + i);
            Map<String, String> v = new HashMap<>(base.plain());
            v.put("target", target.getName());
            ItemStack head = template("player", new Values(v, base.rich()), target);
            String name = target.getName();
            set(slots.get(i), head, type -> askAmount(name));
        }
    }

    @Override
    protected boolean action(String name, String arg) {
        switch (name) {
            case "previous" -> menus.openTransfer(player, page - 1);
            case "next" -> menus.openTransfer(player, page + 1);
            case "type-name" -> menus.prompts().ask(player, "menu.input-pay-player", Map.of(),
                    this::askAmount, () -> menus.openTransfer(player, page));
            default -> {
                return false;
            }
        }
        return true;
    }

    private void askAmount(String target) {
        if (target.equalsIgnoreCase(player.getName())) {
            menus.bank().messages().send(player, "cannot-pay-self");
            result(false);
            if (!isOpen()) menus.openTransfer(player, page);
            return;
        }
        int back = page;
        java.math.BigDecimal before = balance;
        menus.prompts().ask(player, "menu.input-pay-amount", Map.of("target", target), text -> {
            AmountInput input;
            try {
                input = AmountInput.parse(text);
            } catch (AmountInput.InvalidAmountException e) {
                menus.bank().messages().send(player, "invalid-amount", Map.of("input", text));
                menus.openTransfer(player, back);
                return;
            }
            BankService.Outcome done = success -> {
                if (player.isOnline()) menus.open("main", player, before); // the balance counts down there
            };
            menus.bank().pay(player, target, input, done);
        }, () -> menus.openTransfer(player, back));
    }
}
