package dev.direk.dkbank.gui.menus;

import dev.direk.dkbank.bank.Leaderboard;
import dev.direk.dkbank.gui.Menu;
import dev.direk.dkbank.gui.MenuLayout;
import dev.direk.dkbank.gui.MenuManager;
import dev.direk.dkbank.gui.MenuText.Values;
import dev.direk.dkbank.storage.StoreTypes.TopEntry;
import dev.direk.dkbank.util.TimeText;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The leaderboard, from its last update (no database).
 * <p>
 * Settings: {@code slots.entries}; templates {@code first}, {@code second}, {@code third} (optional, for
 * the top three) and {@code entry}. In those: {@code <rank>}, {@code <target>}, {@code <target-balance>},
 * {@code <target-short>}; {@code head: target} shows their skin. Actions: {@code previous}, {@code next}.
 * Fixed items {@code previous}/{@code next} only show when there's a page to go to, {@code empty} only
 * when nobody has money in the bank yet.
 */
public final class TopMenu extends Menu {

    private final Leaderboard.Snapshot top;
    private final int page;

    public TopMenu(MenuManager menus, Player player, MenuLayout layout, Leaderboard.Snapshot top, int page) {
        super(menus, player, layout);
        this.top = top;
        int perPage = Math.max(1, layout.slots("entries").size());
        int pages = Math.max(1, (top.entries().size() + perPage - 1) / perPage);
        this.page = Math.max(1, Math.min(page, pages));
    }

    private int pages() {
        int perPage = Math.max(1, layout.slots("entries").size());
        return Math.max(1, (top.entries().size() + perPage - 1) / perPage);
    }

    @Override
    protected Values values() {
        Map<String, String> v = common();
        int rank = top.rank(player.getUniqueId());
        v.put("rank", rank == 0 ? layout.text("unranked", "-") : String.valueOf(rank));
        v.put("page", String.valueOf(page));
        v.put("pages", String.valueOf(pages()));
        v.put("total", menus.bank().fmt(top.totals().balance()));
        v.put("accounts", String.valueOf(top.totals().accounts()));
        v.put("updated", TimeText.format(Duration.ofMillis(System.currentTimeMillis() - top.time())));
        return new Values(v, richCommon());
    }

    @Override
    protected boolean visible(MenuLayout.Entry entry) {
        return switch (entry.key()) {
            case "previous" -> page > 1;
            case "next" -> page < pages();
            case "empty" -> top.entries().isEmpty();
            default -> true;
        };
    }

    @Override
    protected void draw() {
        List<Integer> slots = layout.slots("entries");
        Values base = values();
        int start = (page - 1) * slots.size();
        for (int i = 0; i < slots.size() && start + i < top.entries().size(); i++) {
            int rank = start + i + 1;
            TopEntry e = top.entries().get(start + i);
            Map<String, String> v = new HashMap<>(base.plain());
            v.put("rank", String.valueOf(rank));
            v.put("target", e.name());
            v.put("target-balance", menus.bank().fmt(e.balance()));
            v.put("target-short", menus.bank().fmtShort(e.balance()));
            Player online = Bukkit.getPlayer(e.uuid());
            var profile = online != null ? online.getPlayerProfile() : Bukkit.createProfile(e.uuid(), e.name());
            String key = switch (rank) {
                case 1 -> "first";
                case 2 -> "second";
                case 3 -> "third";
                default -> "entry";
            };
            ItemStack item = template(key, new Values(v, base.rich()), profile);
            if (item == null) item = template("entry", new Values(v, base.rich()), profile);
            set(slots.get(i), item, null);
        }
    }

    @Override
    protected boolean action(String name, String arg) {
        switch (name) {
            case "previous" -> menus.openTop(player, page - 1);
            case "next" -> menus.openTop(player, page + 1);
            default -> {
                return false;
            }
        }
        return true;
    }
}
