# Placeholders (PlaceholderAPI)

Everything comes from memory, never the database, so scoreboards and tab lists can refresh as often as
they like, from any thread. Offline players only have a balance if they're on the leaderboard.

| Placeholder | Example | |
|---|---|---|
| `%dkbank_balance%` | `$12,500.00` | Formatted like the rest of dkBank |
| `%dkbank_balance_short%` | `$12.5K` | |
| `%dkbank_balance_full%` | `$12,500.00` | Always in full |
| `%dkbank_balance_raw%` | `12500.00` | For other plugins' maths |
| `%dkbank_max_balance%` / `%dkbank_room%` | `$1,000,000.00` | Tier maximum, space left |
| `%dkbank_tier%` | Silver (coloured) | `_plain` without colour, `_id` the name in tiers.yml |
| `%dkbank_next_tier%` / `%dkbank_next_tier_cost%` | Gold / `$150,000.00` | Empty at the top |
| `%dkbank_online_rate%` / `%dkbank_offline_rate%` | `1` | Percent |
| `%dkbank_cap%` | `$100,000.00` | Interest cap |
| `%dkbank_next_payout%` | `35m` | Playtime until the next payout; `_seconds` as a number |
| `%dkbank_locked%` | `false` | Locked by the alt-account limit |
| `%dkbank_rank%` | `7` | Leaderboard place, `-` if not on it |
| `%dkbank_top_name_<n>%` | `%dkbank_top_name_1%` | Leaderboard place n; `-` if empty |
| `%dkbank_top_balance_<n>%` / `%dkbank_top_balance_short_<n>%` | `$5.2M` | |
| `%dkbank_total%` / `%dkbank_total_short%` | `$84.1M` | All banks together |
| `%dkbank_accounts%` | `1520` | Number of accounts |

Test one with `/papi parse me %dkbank_balance%`.
