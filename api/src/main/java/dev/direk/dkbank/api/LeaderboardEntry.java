package dev.direk.dkbank.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One place on the leaderboard.
 *
 * @param rank 1 for the richest
 */
public record LeaderboardEntry(int rank, UUID uuid, String name, BigDecimal balance) {
}
