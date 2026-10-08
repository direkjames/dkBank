package dev.direk.dkbank.gui;

import dev.direk.dkbank.bank.BankService;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Asks a player to type something in chat (an amount, a player name). Works for every player, including
 * Bedrock players through Geyser. The typed line is hidden from chat.
 */
public final class ChatPrompts implements Listener {

    private record Prompt(Consumer<String> answer, Runnable cancelled) {
    }

    private final JavaPlugin plugin;
    private final BankService bank;
    private final Map<UUID, Prompt> waiting = new ConcurrentHashMap<>();

    public ChatPrompts(JavaPlugin plugin, BankService bank) {
        this.plugin = plugin;
        this.bank = bank;
    }

    /**
     * Closes the player's menu and waits for their next chat line.
     *
     * @param messageKey the question, from messages.yml
     * @param answer     gets the typed text (main thread)
     * @param cancelled  runs if they type the cancel word (main thread); not on timeout
     */
    public void ask(Player player, String messageKey, Map<String, String> vars, Consumer<String> answer, Runnable cancelled) {
        UUID uuid = player.getUniqueId();
        Prompt prompt = new Prompt(answer, cancelled);
        waiting.put(uuid, prompt);
        player.closeInventory();
        Map<String, String> v = new java.util.HashMap<>(vars);
        v.put("cancel", bank.settings().menus().cancelWord());
        v.put("seconds", String.valueOf(bank.settings().menus().inputSeconds()));
        bank.messages().send(player, messageKey, v);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (waiting.remove(uuid, prompt)) {
                Player online = Bukkit.getPlayer(uuid);
                if (online != null) bank.messages().send(online, "menu.input-timeout");
            }
        }, bank.settings().menus().inputSeconds() * 20L);
    }

    /** Stops waiting, without a message (e.g. a menu was opened or the player left). */
    public void cancel(UUID uuid) {
        waiting.remove(uuid);
    }

    public boolean isWaiting(UUID uuid) {
        return waiting.containsKey(uuid);
    }

    /**
     * Chat plugins that still use the old chat event see a line before the new event fires, so a typed
     * answer is hidden there too.
     */
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOWEST)
    public void onLegacyChat(org.bukkit.event.player.AsyncPlayerChatEvent event) {
        if (waiting.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onChat(AsyncChatEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Prompt prompt = waiting.get(uuid);
        if (prompt == null) return;
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        bank.runOnMain(() -> {
            if (!waiting.remove(uuid, prompt)) return; // timed out or replaced meanwhile
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) return;
            if (text.equalsIgnoreCase(bank.settings().menus().cancelWord())) {
                bank.messages().send(player, "menu.input-cancelled");
                prompt.cancelled().run();
            } else {
                prompt.answer().accept(text);
            }
        });
    }
}
