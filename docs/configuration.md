# Configuration (config.yml)

Durations accept `30s`, `15m`, `1h`, `7d` or `1d 12h`. Amounts accept plain numbers. Wrong values are
replaced with safe defaults and named in the console; unknown settings (typos) are reported too.

## command

| Setting | Default | |
|---|---|---|
| `aliases` | `[dkbank]` | Extra names for `/bank`. `/dkbank:bank` always works. Restart to apply. |

## currency

| Setting | Default | |
|---|---|---|
| `symbol` | `$` | Currency symbol |
| `symbol-position` | `before` | `before` ($100) or `after` (100$) |
| `show-cents` | `true` | `false` hides cents in text (balances keep them) |
| `thousands-separator` / `decimal-separator` | `,` / `.` | e.g. `.` and `,` for 1.234,56 |
| `compact-suffixes` | `[K, M, B, T, Q]` | Short forms for thousand to quadrillion |
| `short-from` | `0` | Amounts this big or bigger are shown short everywhere ($1.2M). 0 = never |
| `short-decimals` | `1` | Decimals in short amounts (0-2) |
| `use-economy-format` | `false` | Use your economy plugin's formatting instead |

## limits, fees, transfers

| Setting | Default | |
|---|---|---|
| `limits.min-amount` | `1` | Smallest deposit, withdrawal or transfer |
| `limits.max-per-transaction` | `0` | Largest one (0 = none). "all"/"half" are reduced to it |
| `fees.withdraw-percent` | `0` | Taken from withdrawals (100 at 2% gives 98) |
| `fees.transfer-percent` | `0` | Paid by the sender on top (100 at 2% costs 102) |
| `transfers.enabled` | `true` | Allow `/bank pay` |
| `transfers.allow-offline-players` | `true` | Allow sending to offline players |

The maximum bank balance is set per tier in `tiers.yml`.

## interest

See [How interest works](interest.md). Rates, caps and limits are per tier in `tiers.yml`.

| Setting | Default | |
|---|---|---|
| `online.enabled` / `online.period` | `true` / `1h` | Online interest and how much play each payout is for (at least 1m) |
| `offline.enabled` / `offline.period` | `true` / `1d` | Offline interest and its period (at least 1m) |
| `offline.max-time` | `7d` | Time away that counts, at most |
| `notify.online-payout` / `notify.offline-payout` | `true` | Payout messages |
| `notify.login-delay` | `3` | Seconds after joining before the "while you were away" message |
| `notify.sound` | `entity.experience_orb.pickup` | Payout sound, `""` for none |

## afk-detection

| Setting | Default | |
|---|---|---|
| `enabled` | `true` | AFK time earns the offline rate |
| `idle-after` | `5m` | AFK after this long without activity (0 = only placeholders) |
| `placeholders` | EssentialsX, CMI, DirekAntiAFK | PlaceholderAPI placeholders from AFK plugins |
| `afk-values` | `[yes, true]` | Placeholder results that mean AFK |
| `use-afk-plugins` | `true` | Ask plugins that tell dkBank directly who is AFK (e.g. dkCore with dkAFK) |

## alt-limit

See [Alt-account limit](alt-limit.md).

| Setting | Default | |
|---|---|---|
| `enabled` | `true` | |
| `max-accounts` | `3` | Accounts per connection that can use the bank |
| `remember` | `30d` | Logins older than this don't count |
| `locked-can-withdraw` | `true` | Locked accounts can still take their money out |
| `tell-player` | `true` | Tell locked players why when they join |

## menus

| Setting | Default | |
|---|---|---|
| `open-from-commands` | `true` | `/bank`, `/bank deposit`, `/bank withdraw`, `/bank pay`, `/bank tiers`, `/bank history` and `/bank top` (typed on their own) open menus. `false` = chat; `/bank menu` still opens the menu |
| `chat-input-seconds` | `30` | Time to type an amount or name in chat (5-300) |
| `cancel-word` | `cancel` | Typing this cancels |

## leaderboard

| Setting | Default | |
|---|---|---|
| `size` | `100` | Places kept (10-1000) |
| `refresh` | `5m` | How often it's worked out again (at least 1m) |
| `hidden` | `[]` | Names never shown (staff, server accounts) |

## history

| Setting | Default | |
|---|---|---|
| `page-size` | `8` | Lines per chat page (3-20) |
| `date-format` | `MMM d, HH:mm` | [Java date pattern](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/format/DateTimeFormatter.html) |
| `timezone` | `""` | e.g. `Asia/Manila`; empty = the server's |

## update-checker, startup-banner, metrics

| Setting | Default | |
|---|---|---|
| `update-checker` | `true` | Tell staff (`dkbank.admin`) when a new version is out |
| `startup-banner` | `true` | The dkBank banner in the console on startup; `false` = plain log lines |
| `metrics` | `true` | Anonymous usage numbers on bstats.org (no player data) |

## storage

See [Installation → MySQL](installation.md#mysql--mariadb). Changes need a restart.

| Setting | Default | |
|---|---|---|
| `type` | `sqlite` | `sqlite`, `mysql` or `mariadb` |
| `table-prefix` | `dkbank_` | Letters, numbers and `_` |
| `sqlite.file` | `bank.db` | In the plugin folder |
| `mysql.host`, `port`, `database`, `username`, `password`, `use-ssl` | | Connection |
| `mysql.pool-size` | `6` | Connections kept open (2-20) |
