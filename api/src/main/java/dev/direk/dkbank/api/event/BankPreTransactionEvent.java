package dev.direk.dkbank.api.event;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;

/**
 * A player is about to deposit, withdraw or send money (by command or menu). Cancel it to stop them;
 * nothing has changed yet. Not fired for staff commands or API calls.
 */
public final class BankPreTransactionEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    /** What the player is trying to do. */
    public enum Action { DEPOSIT, WITHDRAW, TRANSFER }

    private final Action action;
    private final @Nullable BigDecimal amount;
    private final @Nullable String target;
    private boolean cancelled;
    private @Nullable Component cancelMessage;

    public BankPreTransactionEvent(Player player, Action action, @Nullable BigDecimal amount, @Nullable String target) {
        super(player);
        this.action = action;
        this.amount = amount;
        this.target = target;
    }

    public Action getAction() {
        return action;
    }

    /**
     * @return the amount asked for, or null when the player asked for "all" or "half" (worked out later
     * against their balance)
     */
    public @Nullable BigDecimal getAmount() {
        return amount;
    }

    /** @return for {@link Action#TRANSFER}: the name of the player receiving the money; otherwise null */
    public @Nullable String getTarget() {
        return target;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    /** @return the message sent to the player when cancelled, or null for none */
    public @Nullable Component getCancelMessage() {
        return cancelMessage;
    }

    /** Sets a message sent to the player when the event is cancelled. */
    public void setCancelMessage(@Nullable Component message) {
        this.cancelMessage = message;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
