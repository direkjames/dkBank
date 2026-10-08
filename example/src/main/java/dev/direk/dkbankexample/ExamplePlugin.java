package dev.direk.dkbankexample;

import dev.direk.dkbank.api.BankResult;
import dev.direk.dkbank.api.BankTier;
import dev.direk.dkbank.api.DkBankAPI;
import dev.direk.dkbank.api.event.BankPreTransactionEvent;
import dev.direk.dkbank.api.event.BankTierChangeEvent;
import dev.direk.dkbank.api.event.BankTransactionEvent;
import dev.direk.dkbank.api.event.TransactionKind;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;

/**
 * A small addon using the dkBank API:
 * <ul>
 *     <li>{@code /dailybonus}: adds 100 to your bank once (shows {@link DkBankAPI#give})</li>
 *     <li>{@code /mybank}: your balance, tier and leaderboard place (shows reading the API)</li>
 *     <li>no deposits in the nether (shows cancelling {@link BankPreTransactionEvent})</li>
 *     <li>logs big transactions and tier changes (shows {@link BankTransactionEvent} and
 *     {@link BankTierChangeEvent})</li>
 * </ul>
 */
public final class ExamplePlugin extends JavaPlugin implements Listener {

    private static final BigDecimal BIG = new BigDecimal("100000");
    private final java.util.Set<java.util.UUID> claimed = new java.util.HashSet<>();

    @Override
    public void onEnable() {
        getLogger().info("Using dkBank " + DkBankAPI.get().version());
        getServer().getPluginManager().registerEvents(this, this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            event.registrar().register(Commands.literal("dailybonus").executes(ctx -> {
                if (ctx.getSource().getSender() instanceof Player player) dailyBonus(player);
                return 1;
            }).build(), "Get 100 in your bank");
            event.registrar().register(Commands.literal("mybank").executes(ctx -> {
                if (ctx.getSource().getSender() instanceof Player player) myBank(player);
                return 1;
            }).build(), "Your bank at a glance");
        });
    }

    private void dailyBonus(Player player) {
        if (!claimed.add(player.getUniqueId())) {
            player.sendMessage(Component.text("You already claimed it.", NamedTextColor.RED));
            return;
        }
        DkBankAPI bank = DkBankAPI.get();
        // The future completes on the main thread, so using the player here is safe.
        bank.give(player.getUniqueId(), new BigDecimal("100"), "Daily bonus").thenAccept((BankResult result) -> {
            if (result.success()) {
                player.sendMessage(Component.text("100 added! Bank: " + bank.format(result.balance()), NamedTextColor.GREEN));
            } else {
                claimed.remove(player.getUniqueId());
                player.sendMessage(Component.text("Couldn't add it: " + result.failure(), NamedTextColor.RED));
            }
        });
    }

    private void myBank(Player player) {
        DkBankAPI bank = DkBankAPI.get();
        BankTier tier = bank.tierOf(player);
        String balance = bank.cachedBalance(player.getUniqueId()).map(bank::format).orElse("?");
        int rank = bank.rank(player.getUniqueId());
        player.sendMessage(Component.text("Bank: " + balance + " · Tier: " + tier.id()
                + " · Place: " + (rank == 0 ? "-" : "#" + rank), NamedTextColor.GOLD));
    }

    /** No banking from the nether. */
    @EventHandler
    public void onPreTransaction(BankPreTransactionEvent event) {
        if (event.getAction() == BankPreTransactionEvent.Action.DEPOSIT
                && event.getPlayer().getWorld().getEnvironment() == org.bukkit.World.Environment.NETHER) {
            event.setCancelled(true);
            event.setCancelMessage(Component.text("The bank doesn't reach the nether.", NamedTextColor.RED));
        }
    }

    @EventHandler
    public void onTransaction(BankTransactionEvent event) {
        if (event.getAmount().compareTo(BIG) >= 0 && event.getKind() != TransactionKind.ADMIN_SET) {
            getLogger().info(event.getAccount() + " " + event.getKind() + " " + event.getAmount().toPlainString()
                    + " (balance " + event.getBalance().toPlainString() + ")");
        }
    }

    @EventHandler
    public void onTierChange(BankTierChangeEvent event) {
        getLogger().info(event.getAccount() + " is now " + event.getTier().id() + " (" + event.getCause() + ")");
    }
}
