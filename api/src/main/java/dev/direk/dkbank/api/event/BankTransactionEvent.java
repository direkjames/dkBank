package dev.direk.dkbank.api.event;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Money in a bank changed. It has already happened and is saved. Fired for every change, whatever caused
 * it; a transfer fires two (the sender's {@link TransactionKind#TRANSFER_OUT} and the receiver's
 * {@link TransactionKind#TRANSFER_IN}).
 */
public final class BankTransactionEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID account;
    private final TransactionKind kind;
    private final BigDecimal amount;
    private final BigDecimal fee;
    private final BigDecimal balance;
    private final @Nullable UUID other;
    private final @Nullable String source;

    public BankTransactionEvent(UUID account, TransactionKind kind, BigDecimal amount, BigDecimal fee, BigDecimal balance,
                                @Nullable UUID other, @Nullable String source) {
        this.account = account;
        this.kind = kind;
        this.amount = amount;
        this.fee = fee;
        this.balance = balance;
        this.other = other;
        this.source = source;
    }

    /** @return whose bank changed */
    public UUID getAccount() {
        return account;
    }

    /** @return the account's owner if they're online */
    public @Nullable Player getPlayer() {
        return Bukkit.getPlayer(account);
    }

    public TransactionKind getKind() {
        return kind;
    }

    /** @return the amount (for {@link TransactionKind#ADMIN_SET}, the new balance) */
    public BigDecimal getAmount() {
        return amount;
    }

    /** @return the fee charged (zero if none) */
    public BigDecimal getFee() {
        return fee;
    }

    /** @return the balance afterwards */
    public BigDecimal getBalance() {
        return balance;
    }

    /** @return for transfers: the other account; otherwise null */
    public @Nullable UUID getOther() {
        return other;
    }

    /**
     * @return who caused it: a staff member's name, a plugin's source text, "online"/"offline" for
     * interest; null for the player themselves
     */
    public @Nullable String getSource() {
        return source;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
