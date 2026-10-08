package dev.direk.dkbank.gui.menus;

import dev.direk.dkbank.config.Messages;
import dev.direk.dkbank.gui.Menu;
import dev.direk.dkbank.gui.MenuLayout;
import dev.direk.dkbank.gui.MenuManager;
import dev.direk.dkbank.gui.MenuText;
import dev.direk.dkbank.gui.MenuText.Values;
import dev.direk.dkbank.storage.StoreTypes.Entry;
import dev.direk.dkbank.storage.StoreTypes.Page;
import dev.direk.dkbank.tier.Tier;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The player's transactions, newest first, one item each in {@code slots.entries}.
 * <p>
 * Template {@code entry}: {@code <line>} is the line from messages.yml (history.types), plus
 * {@code <date>}, {@code <amount>}, {@code <fee>}, {@code <balance-after>}, {@code <other>} and
 * {@code <material>} (from {@code materials}, by type). Actions: {@code previous}, {@code next}.
 * Fixed items named {@code previous}/{@code next} only show when there's a page to go to, and
 * {@code empty} only when there's no history.
 */
public final class HistoryMenu extends Menu {

    private final int requested;
    private @Nullable Page page;

    public HistoryMenu(MenuManager menus, Player player, MenuLayout layout, int page) {
        super(menus, player, layout);
        this.requested = Math.max(1, page);
    }

    @Override
    protected void onOpen() {
        UUID uuid = player.getUniqueId();
        int size = Math.max(1, layout.slots("entries").size());
        menus.bank().query("load bank history", () -> menus.bank().store().history(uuid, requested, size), loaded -> {
            page = loaded;
            if (isOpen()) render();
        });
    }

    private int current() {
        return page == null ? requested : page.page();
    }

    private int pages() {
        return page == null ? requested : page.pages();
    }

    @Override
    protected Values values() {
        Map<String, String> v = common();
        v.put("page", String.valueOf(current()));
        v.put("pages", page == null ? "…" : String.valueOf(pages()));
        v.put("total", page == null ? "…" : String.valueOf(page.total()));
        return new Values(v, richCommon());
    }

    @Override
    protected boolean visible(MenuLayout.Entry entry) {
        return switch (entry.key()) {
            case "previous" -> page != null && current() > 1;
            case "next" -> page != null && current() < pages();
            case "empty" -> page != null && page.entries().isEmpty();
            default -> true;
        };
    }

    @Override
    protected void draw() {
        if (page == null) return;
        List<Integer> slots = layout.slots("entries");
        Values base = values();
        Messages m = menus.bank().messages();
        for (int i = 0; i < Math.min(slots.size(), page.entries().size()); i++) {
            Entry e = page.entries().get(i);
            String type = e.type().name().toLowerCase(Locale.ROOT).replace('_', '-');
            Map<String, String> v = new HashMap<>(base.plain());
            v.put("amount", menus.bank().fmt(e.amount()));
            v.put("fee", menus.bank().fmt(e.fee()));
            v.put("balance-after", menus.bank().fmt(e.balanceAfter()));
            v.put("other", e.otherName() == null ? "-" : e.otherName());
            v.put("date", menus.bank().settings().dateFormat().format(Instant.ofEpochMilli(e.time())));
            v.put("material", layout.file().getString("materials." + type, layout.file().getString("materials.default", "PAPER")));

            // The same line as /bank history, with its own values (the other player's name stays plain text).
            Map<String, String> lineVars = new HashMap<>();
            lineVars.put("amount", v.get("amount"));
            lineVars.put("fee", v.get("fee"));
            lineVars.put("balance", v.get("balance-after"));
            lineVars.put("player", v.get("other"));
            lineVars.put("actor", e.actor() == null ? "?" : e.actor());
            lineVars.put("date", v.get("date"));
            String tierName = e.otherName() == null ? menus.bank().tiers().first().displayName()
                    : menus.bank().tiers().byId(e.otherName()).map(Tier::displayName).orElse(e.otherName());
            String text = m.raw("history.types." + type);
            Component line = text.isEmpty() ? Component.text(type)
                    : m.renderText(text, lineVars, Map.of("tier", tierName));

            Map<String, Component> rich = new HashMap<>(base.rich());
            rich.put("line", line);
            set(slots.get(i), template("entry", new Values(v, rich), null), null);
        }
    }

    @Override
    protected boolean action(String name, String arg) {
        switch (name) {
            case "previous" -> menus.openHistory(player, current() - 1);
            case "next" -> menus.openHistory(player, current() + 1);
            default -> {
                return false;
            }
        }
        return true;
    }
}
