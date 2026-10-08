# Changelog

## 1.0.0 — first release

Everything below, ready for sale.

- **Bank:** deposit, withdraw and send money between banks; `1.5k`, `2m`, `all`, `half`; fees, limits,
  full history; staff give, take, set and view anyone's history.
- **Interest:** online interest per hour played and offline interest (compounding) for time away, with
  separate rates; AFK players earn the offline rate; interest on the lowest balance since the last
  payout; caps, maximum per payout (scaled by time) and maximum balance; payout messages and sounds.
- **Tiers:** buyable bank tiers with their own rates, interest cap and maximum balance; tiers from rank
  permissions; upgrade confirmation; decimal rates.
- **Menus:** main, deposit, withdraw, send money, tiers, upgrade confirmation, history and leaderboard;
  every menu editable in `menus/*.yml`, custom heads from minecraft-heads.com, live payout countdown,
  animated balance, typed amounts hidden from chat.
- **Alt-account limit:** only the first accounts per connection can use the bank; salted address hashes;
  staff can allow accounts.
- **Integrations:** 24 PlaceholderAPI placeholders served from memory; `/bank top`; economy report for
  staff; short amounts ($1.2M).
- **Developer API:** accounts, give, take, transfer, tiers, leaderboard; cancellable events.
- **Safety:** every change is one database transaction (crash-tested); SQLite or MySQL/MariaDB with
  automatic upgrades; a config file with a mistake is never overwritten; typo warnings.
- **New in 1.0.0:** update checker for staff, optional anonymous bStats, `/bank admin info` for support,
  full documentation.

### Beta history

- 0.9.0 — hardening: crash and load tests, two code reviews, heartbeat batching.
- 0.6.0 — Developer API and events. 0.5.0 — placeholders, leaderboard, economy report.
- 0.4.0 — menus, alt-account limit, custom heads. 0.3.x — tiers. 0.2.0 — interest. 0.1.0 — core bank.
