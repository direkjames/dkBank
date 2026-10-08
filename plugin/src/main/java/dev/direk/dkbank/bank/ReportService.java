package dev.direk.dkbank.bank;

import dev.direk.dkbank.config.Messages;
import dev.direk.dkbank.money.Money;
import dev.direk.dkbank.storage.StoreTypes.Activity;
import dev.direk.dkbank.storage.StoreTypes.Earner;
import dev.direk.dkbank.storage.StoreTypes.TopEntry;
import dev.direk.dkbank.storage.StoreTypes.Totals;
import dev.direk.dkbank.storage.TransactionType;
import dev.direk.dkbank.util.TimeText;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static dev.direk.dkbank.bank.BankService.vars;

/** /bank top in chat, and the economy report for staff. */
public final class ReportService {

    /** Lines per page of /bank top in chat. */
    public static final int TOP_PAGE_SIZE = 10;

    private final BankService bank;
    private final Leaderboard leaderboard;

    public ReportService(BankService bank, Leaderboard leaderboard) {
        this.bank = bank;
        this.leaderboard = leaderboard;
    }

    // ------------------------------------------------------------------ /bank top

    public void showTop(CommandSender sender, int page) {
        Messages m = bank.messages();
        Leaderboard.Snapshot top = leaderboard.snapshot();
        if (top.entries().isEmpty()) {
            m.send(sender, "top.empty");
            return;
        }
        int pages = Math.max(1, (top.entries().size() + TOP_PAGE_SIZE - 1) / TOP_PAGE_SIZE);
        int current = Math.max(1, Math.min(page, pages));
        String ago = TimeText.format(Duration.ofMillis(System.currentTimeMillis() - top.time()));
        m.send(sender, "top.header", vars("page", String.valueOf(current), "pages", String.valueOf(pages), "ago", ago,
                "total", bank.fmt(top.totals().balance())));
        int start = (current - 1) * TOP_PAGE_SIZE;
        for (int i = start; i < Math.min(start + TOP_PAGE_SIZE, top.entries().size()); i++) {
            TopEntry e = top.entries().get(i);
            m.send(sender, "top.entry", vars("rank", String.valueOf(i + 1), "player", e.name(),
                    "balance", bank.fmt(e.balance()), "short", bank.fmtShort(e.balance())));
        }
        if (sender instanceof Player player) {
            int rank = top.rank(player.getUniqueId());
            if (rank > 0) m.send(sender, "top.you", vars("rank", String.valueOf(rank)));
            else m.send(sender, "top.you-unranked", vars("size", String.valueOf(top.entries().size())));
        }
        if (pages > 1) m.send(sender, "top.footer", vars("page", String.valueOf(current), "pages", String.valueOf(pages)));
    }

    // ------------------------------------------------------------------ economy report

    private record Report(Totals totals, Map<TransactionType, Activity> day, Map<TransactionType, Activity> period,
                          List<Earner> earners) {
    }

    /** Money in banks, interest created (to watch inflation), upgrades, fees and the top interest earners. */
    public void economy(CommandSender sender, int days) {
        long now = System.currentTimeMillis();
        long dayAgo = now - Duration.ofDays(1).toMillis();
        long periodAgo = now - Duration.ofDays(days).toMillis();
        bank.query("build the economy report", () -> new Report(bank.store().totals(),
                byType(bank.store().activity(dayAgo)), byType(bank.store().activity(periodAgo)),
                bank.store().topBy(TransactionType.INTEREST, periodAgo, 5)), report -> send(sender, days, report));
    }

    private static Map<TransactionType, Activity> byType(List<Activity> list) {
        Map<TransactionType, Activity> map = new EnumMap<>(TransactionType.class);
        for (Activity a : list) map.put(a.type(), a);
        return map;
    }

    private static BigDecimal amount(Map<TransactionType, Activity> map, TransactionType type) {
        Activity a = map.get(type);
        return a == null ? Money.ZERO : a.amount();
    }

    private static long count(Map<TransactionType, Activity> map, TransactionType type) {
        Activity a = map.get(type);
        return a == null ? 0 : a.count();
    }

    private void send(CommandSender sender, int days, Report r) {
        BigDecimal interestPeriod = amount(r.period(), TransactionType.INTEREST);
        BigDecimal fees = r.period().values().stream().map(Activity::fees).reduce(Money.ZERO, BigDecimal::add);
        BigDecimal share = r.totals().balance().signum() == 0 ? BigDecimal.ZERO
                : interestPeriod.multiply(BigDecimal.valueOf(100)).divide(r.totals().balance(), 2, RoundingMode.HALF_UP);
        Map<String, String> v = vars(
                "days", String.valueOf(days),
                "total", bank.fmt(r.totals().balance()),
                "accounts", String.valueOf(r.totals().accounts()),
                "interest-day", bank.fmt(amount(r.day(), TransactionType.INTEREST)),
                "interest-period", bank.fmt(interestPeriod),
                "interest-average", bank.fmt(interestPeriod.divide(BigDecimal.valueOf(days), Money.SCALE, RoundingMode.DOWN)),
                "interest-share", share.stripTrailingZeros().toPlainString(),
                "deposits", bank.fmt(amount(r.period(), TransactionType.DEPOSIT)),
                "withdrawals", bank.fmt(amount(r.period(), TransactionType.WITHDRAW)),
                "transfers", bank.fmt(amount(r.period(), TransactionType.TRANSFER_OUT)),
                "upgrades", bank.fmt(amount(r.period(), TransactionType.UPGRADE)),
                "upgrade-count", String.valueOf(count(r.period(), TransactionType.UPGRADE)),
                "fees", bank.fmt(fees));
        Messages m = bank.messages();
        m.send(sender, "admin.economy", v);
        if (r.earners().isEmpty()) return;
        m.send(sender, "admin.economy-earners", v);
        for (int i = 0; i < r.earners().size(); i++) {
            Earner e = r.earners().get(i);
            m.send(sender, "admin.economy-earner", vars("rank", String.valueOf(i + 1), "player", e.name(),
                    "amount", bank.fmt(e.amount())));
        }
    }
}
