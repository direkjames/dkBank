package dev.direk.dkbank.api.event;

import dev.direk.dkbank.api.BankTier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * An account's bought tier changed. It has already happened and is saved. Permission tiers (from ranks)
 * don't fire this.
 */
public final class BankTierChangeEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    /** Why the tier changed. */
    public enum Cause {
        /** The player bought it. */
        UPGRADE,
        /** Staff set it (/bank admin tier). */
        ADMIN,
        /** Another plugin set it through the API. */
        PLUGIN
    }

    private final UUID account;
    private final BankTier tier;
    private final Cause cause;

    public BankTierChangeEvent(UUID account, BankTier tier, Cause cause) {
        this.account = account;
        this.tier = tier;
        this.cause = cause;
    }

    public UUID getAccount() {
        return account;
    }

    /** @return the account's owner if they're online */
    public @Nullable Player getPlayer() {
        return Bukkit.getPlayer(account);
    }

    /** @return the new bought tier */
    public BankTier getTier() {
        return tier;
    }

    public Cause getCause() {
        return cause;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
