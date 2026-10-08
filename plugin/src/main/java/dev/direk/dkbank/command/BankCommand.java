package dev.direk.dkbank.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.direk.dkbank.DkBankPlugin;
import dev.direk.dkbank.bank.BankService;
import dev.direk.dkbank.money.AmountInput;
import dev.direk.dkbank.tier.Tier;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * {@code /bank} and its subcommands.
 */
public final class BankCommand {

    public static final String NAME = "bank";

    private static final List<String> AMOUNT_SUGGESTIONS = List.of("100", "1k", "10k", "all", "half");

    private final DkBankPlugin plugin;

    public BankCommand(DkBankPlugin plugin) {
        this.plugin = plugin;
    }

    private BankService bank() {
        return plugin.bank();
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(NAME)
                .requires(perm("dkbank.use"))
                .executes(ctx -> menuOr(ctx, "main", () -> overview(ctx)))
                .then(Commands.literal("help").executes(this::help))
                .then(Commands.literal("menu").executes(ctx -> {
                    Player player = player(ctx);
                    if (player != null) plugin.menus().open("main", player);
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("balance")
                        .executes(this::overview)
                        .then(player("player").requires(perm("dkbank.balance.others"))
                                .executes(ctx -> {
                                    bank().showBalance(sender(ctx), StringArgumentType.getString(ctx, "player"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("deposit").requires(perm("dkbank.deposit"))
                        .executes(ctx -> menuOr(ctx, "deposit", () -> usage(ctx, "/bank deposit <amount>")))
                        .then(amount().executes(ctx -> playerAmount(ctx, bank()::deposit))))
                .then(Commands.literal("withdraw").requires(perm("dkbank.withdraw"))
                        .executes(ctx -> menuOr(ctx, "withdraw", () -> usage(ctx, "/bank withdraw <amount>")))
                        .then(amount().executes(ctx -> playerAmount(ctx, bank()::withdraw))))
                .then(Commands.literal("pay").requires(perm("dkbank.pay"))
                        .executes(ctx -> menuOr(ctx, "transfer", () -> usage(ctx, "/bank pay <player> <amount>")))
                        .then(player("player").then(amount().executes(this::pay))))
                .then(Commands.literal("history").requires(perm("dkbank.history"))
                        .executes(ctx -> menuOr(ctx, "history", () -> history(ctx, 1)))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> history(ctx, IntegerArgumentType.getInteger(ctx, "page")))))
                .then(Commands.literal("interest").requires(perm("dkbank.interest"))
                        .executes(ctx -> {
                            Player player = player(ctx);
                            if (player != null) plugin.interest().showInfo(player);
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("top").requires(perm("dkbank.top"))
                        .executes(ctx -> menuOr(ctx, "top", () -> {
                            plugin.reports().showTop(sender(ctx), 1);
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1)).executes(ctx -> {
                            plugin.reports().showTop(sender(ctx), IntegerArgumentType.getInteger(ctx, "page"));
                            return Command.SINGLE_SUCCESS;
                        })))
                .then(Commands.literal("tiers").requires(perm("dkbank.tiers"))
                        .executes(ctx -> menuOr(ctx, "tiers", () -> {
                            Player player = player(ctx);
                            if (player != null) plugin.tiers().showTiers(player);
                            return Command.SINGLE_SUCCESS;
                        })))
                .then(Commands.literal("upgrade").requires(perm("dkbank.upgrade"))
                        .executes(ctx -> {
                            Player player = player(ctx);
                            if (player != null) plugin.tiers().offer(player);
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.literal("confirm").executes(ctx -> {
                            Player player = player(ctx);
                            if (player != null) plugin.tiers().confirm(player);
                            return Command.SINGLE_SUCCESS;
                        })))
                .then(admin());
        return root.build();
    }

    private LiteralArgumentBuilder<CommandSourceStack> admin() {
        return Commands.literal("admin")
                .requires(src -> src.getSender().hasPermission("dkbank.admin")
                        || src.getSender().hasPermission("dkbank.admin.give")
                        || src.getSender().hasPermission("dkbank.admin.take")
                        || src.getSender().hasPermission("dkbank.admin.set")
                        || src.getSender().hasPermission("dkbank.admin.history")
                        || src.getSender().hasPermission("dkbank.admin.tier")
                        || src.getSender().hasPermission("dkbank.admin.alts")
                        || src.getSender().hasPermission("dkbank.admin.economy")
                        || src.getSender().hasPermission("dkbank.admin.reload"))
                .executes(this::help)
                .then(Commands.literal("give").requires(perm("dkbank.admin.give"))
                        .then(player("player").then(amount().executes(ctx -> adminAmount(ctx, bank()::adminGive)))))
                .then(Commands.literal("take").requires(perm("dkbank.admin.take"))
                        .then(player("player").then(amount().executes(ctx -> adminAmount(ctx, bank()::adminTake)))))
                .then(Commands.literal("set").requires(perm("dkbank.admin.set"))
                        .then(player("player").then(Commands.argument("amount", StringArgumentType.word())
                                .suggests(suggestions(List.of("0", "1000", "1m")))
                                .executes(ctx -> adminAmount(ctx, bank()::adminSet)))))
                .then(Commands.literal("balance").requires(perm("dkbank.balance.others"))
                        .then(player("player").executes(ctx -> {
                            bank().showBalance(sender(ctx), StringArgumentType.getString(ctx, "player"));
                            return Command.SINGLE_SUCCESS;
                        })))
                .then(Commands.literal("history").requires(perm("dkbank.admin.history"))
                        .then(player("player")
                                .executes(ctx -> adminHistory(ctx, 1))
                                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                        .executes(ctx -> adminHistory(ctx, IntegerArgumentType.getInteger(ctx, "page"))))))
                .then(Commands.literal("tier").requires(perm("dkbank.admin.tier"))
                        .then(player("player").then(Commands.argument("tier", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    String typed = builder.getRemainingLowerCase();
                                    for (Tier tier : bank().tiers().all()) {
                                        if (tier.id().startsWith(typed)) builder.suggest(tier.id());
                                    }
                                    if ("default".startsWith(typed)) builder.suggest("default");
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> {
                                    plugin.tiers().adminSet(sender(ctx), StringArgumentType.getString(ctx, "player"),
                                            StringArgumentType.getString(ctx, "tier"));
                                    return Command.SINGLE_SUCCESS;
                                }))))
                .then(Commands.literal("economy").requires(perm("dkbank.admin.economy"))
                        .executes(ctx -> {
                            plugin.reports().economy(sender(ctx), 7);
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.argument("days", IntegerArgumentType.integer(1, 365)).executes(ctx -> {
                            plugin.reports().economy(sender(ctx), IntegerArgumentType.getInteger(ctx, "days"));
                            return Command.SINGLE_SUCCESS;
                        })))
                .then(Commands.literal("alts").requires(perm("dkbank.admin.alts"))
                        .then(player("player")
                                .executes(ctx -> {
                                    bank().adminAlts(sender(ctx), StringArgumentType.getString(ctx, "player"));
                                    return Command.SINGLE_SUCCESS;
                                })
                                .then(Commands.literal("allow").executes(ctx -> {
                                    bank().adminAltExempt(sender(ctx), StringArgumentType.getString(ctx, "player"), true);
                                    return Command.SINGLE_SUCCESS;
                                }))
                                .then(Commands.literal("reset").executes(ctx -> {
                                    bank().adminAltExempt(sender(ctx), StringArgumentType.getString(ctx, "player"), false);
                                    return Command.SINGLE_SUCCESS;
                                }))))
                .then(Commands.literal("reload").requires(perm("dkbank.admin.reload")).executes(this::reload));
    }

    // ------------------------------------------------------------------ executors

    /** Opens a menu for players when menus.open-from-commands is on; otherwise runs the chat version. */
    private int menuOr(CommandContext<CommandSourceStack> ctx, String menu, java.util.function.IntSupplier chat) {
        if (sender(ctx) instanceof Player player && bank().settings().menus().openFromCommands()) {
            plugin.menus().open(menu, player);
            return Command.SINGLE_SUCCESS;
        }
        return chat.getAsInt();
    }

    private int usage(CommandContext<CommandSourceStack> ctx, String usage) {
        bank().messages().send(sender(ctx), "usage", Map.of("usage", usage));
        return 0;
    }

    private int overview(CommandContext<CommandSourceStack> ctx) {
        if (sender(ctx) instanceof Player player) {
            bank().showOwnBalance(player);
            bank().messages().send(player, "hint");
        } else {
            help(ctx);
        }
        return Command.SINGLE_SUCCESS;
    }

    private int help(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = sender(ctx);
        bank().messages().send(sender, "help.player");
        if (sender.hasPermission("dkbank.admin")) bank().messages().send(sender, "help.admin");
        return Command.SINGLE_SUCCESS;
    }

    private int playerAmount(CommandContext<CommandSourceStack> ctx, BiConsumer<Player, AmountInput> action) {
        Player player = player(ctx);
        AmountInput amount = amount(ctx);
        if (player != null && amount != null) action.accept(player, amount);
        return Command.SINGLE_SUCCESS;
    }

    private int pay(CommandContext<CommandSourceStack> ctx) {
        Player player = player(ctx);
        AmountInput amount = amount(ctx);
        if (player != null && amount != null) {
            bank().pay(player, StringArgumentType.getString(ctx, "player"), amount);
        }
        return Command.SINGLE_SUCCESS;
    }

    private int history(CommandContext<CommandSourceStack> ctx, int page) {
        Player player = player(ctx);
        if (player != null) bank().showHistory(player, page);
        return Command.SINGLE_SUCCESS;
    }

    private interface AdminAction {
        void run(CommandSender sender, String player, BigDecimal amount);
    }

    private int adminAmount(CommandContext<CommandSourceStack> ctx, AdminAction action) {
        CommandSender sender = sender(ctx);
        String text = StringArgumentType.getString(ctx, "amount");
        BigDecimal amount;
        if (text.equals("0")) {
            amount = BigDecimal.ZERO.setScale(2); // only meaningful for "set"
        } else {
            AmountInput input = amount(ctx);
            if (input == null) return 0;
            if (input.isShare() || input.fixed() == null) {
                bank().messages().send(sender, "admin.no-shares");
                return 0;
            }
            amount = input.fixed();
        }
        action.run(sender, StringArgumentType.getString(ctx, "player"), amount);
        return Command.SINGLE_SUCCESS;
    }

    private int adminHistory(CommandContext<CommandSourceStack> ctx, int page) {
        bank().showHistory(sender(ctx), StringArgumentType.getString(ctx, "player"), page);
        return Command.SINGLE_SUCCESS;
    }

    private int reload(CommandContext<CommandSourceStack> ctx) {
        plugin.reloadFiles();
        bank().messages().send(sender(ctx), "admin.reloaded");
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------ arguments and helpers

    private static java.util.function.Predicate<CommandSourceStack> perm(String permission) {
        return src -> src.getSender().hasPermission(permission);
    }

    private static CommandSender sender(CommandContext<CommandSourceStack> ctx) {
        return ctx.getSource().getSender();
    }

    private @Nullable Player player(CommandContext<CommandSourceStack> ctx) {
        if (sender(ctx) instanceof Player player) return player;
        bank().messages().send(sender(ctx), "player-only");
        return null;
    }

    private @Nullable AmountInput amount(CommandContext<CommandSourceStack> ctx) {
        String text = StringArgumentType.getString(ctx, "amount");
        try {
            return AmountInput.parse(text);
        } catch (AmountInput.InvalidAmountException e) {
            bank().messages().send(sender(ctx), "invalid-amount", Map.of("input", text));
            return null;
        }
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> amount() {
        return Commands.argument("amount", StringArgumentType.word()).suggests(suggestions(AMOUNT_SUGGESTIONS));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> player(String name) {
        return Commands.argument(name, StringArgumentType.word()).suggests((ctx, builder) -> {
            String typed = builder.getRemainingLowerCase();
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(typed)) builder.suggest(online.getName());
            }
            return builder.buildFuture();
        });
    }

    private static SuggestionProvider<CommandSourceStack> suggestions(List<String> values) {
        return (ctx, builder) -> {
            String typed = builder.getRemainingLowerCase();
            values.stream().filter(v -> v.startsWith(typed)).forEach(builder::suggest);
            return builder.buildFuture();
        };
    }
}
