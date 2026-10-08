package dev.direk.dkbank.api.event;

import dev.direk.dkbank.api.BankTier;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jspecify.annotations.Nullable;

/**
 * A player confirmed buying a tier and is about to pay. Cancel it to stop the upgrade; nothing has
 * changed yet.
 */
public final class BankUpgradeEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final BankTier from;
    private final BankTier to;
    private boolean cancelled;
    private @Nullable Component cancelMessage;

    public BankUpgradeEvent(Player player, BankTier from, BankTier to) {
        super(player);
        this.from = from;
        this.to = to;
    }

    /** @return the player's tier now */
    public BankTier getFrom() {
        return from;
    }

    /** @return the tier they're buying ({@link BankTier#cost()} is the price) */
    public BankTier getTo() {
        return to;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

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
