/**
 * dkBank events. All are fired on the server's main thread.
 * <ul>
 *     <li>{@link dev.direk.dkbank.api.event.BankPreTransactionEvent}: a player is about to deposit,
 *     withdraw or send money (cancellable)</li>
 *     <li>{@link dev.direk.dkbank.api.event.BankTransactionEvent}: money in a bank changed, for any
 *     reason (deposits, interest, admin, other plugins...)</li>
 *     <li>{@link dev.direk.dkbank.api.event.BankUpgradeEvent}: a player is about to buy a tier
 *     (cancellable)</li>
 *     <li>{@link dev.direk.dkbank.api.event.BankTierChangeEvent}: an account's bought tier changed</li>
 * </ul>
 */
@NullMarked
package dev.direk.dkbank.api.event;

import org.jspecify.annotations.NullMarked;
