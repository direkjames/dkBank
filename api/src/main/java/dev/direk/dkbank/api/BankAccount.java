package dev.direk.dkbank.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A bank account at the moment it was read.
 *
 * @param uuid    the player
 * @param name    their name when they last joined
 * @param balance money in the bank
 * @param tier    id of the tier they bought, without permission tiers (the first tier if none)
 */
public record BankAccount(UUID uuid, String name, BigDecimal balance, String tier) {
}
