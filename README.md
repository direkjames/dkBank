# dkBank

A premium bank plugin for Paper and Purpur with separate online and offline interest.
See [ROADMAP.md](ROADMAP.md) for the plan.

> Proprietary software. See [LICENSE](LICENSE). The Developer API in `api/` is MIT-licensed.

## Supported servers

| | |
|---|---|
| Servers | Paper, Purpur |
| Minecraft | 1.21.4 – 26.3 (one jar) |
| Java | 21 or newer (1.21.x servers run 21, 26.x servers run 25) |

## Project layout

```
api/      Developer API (MIT): what other plugins compile against
plugin/   the plugin itself (proprietary): built into dkBank-<version>.jar
gradle/libs.versions.toml   every dependency version in one place
```

Packages: `dev.direk.dkbank` (plugin), `dev.direk.dkbank.api` (API).

## Building

Open the folder in IntelliJ IDEA and let Gradle sync. If you don't have JDK 21, Gradle downloads it.

```
./gradlew build
```

The plugin jar is `plugin/build/libs/dkBank-<version>.jar` (the only jar there). Bundled libraries (HikariCP) are moved into
`dev.direk.dkbank.libs` inside the jar so they can't clash with other plugins.

Every push to GitHub runs the same build and tests (see `.github/workflows/build.yml`). The jar is
attached to each run under **Actions → the run → Artifacts**.

## Test servers

Each task builds the plugin, downloads the server, and starts it with dkBank, **Vault 1.7.3 and EssentialsX
2.22.0** installed, so deposits and withdrawals work like on a real server. Every version gets its own folder
under `run/` (ignored by git). The right Java version is downloaded if needed.

| Task | Server |
|---|---|
| `runServer` | Paper 26.3 (quick default) |
| `runPaper-1.21.4`, `runPaper-1.21.11`, `runPaper-26.1`, `runPaper-26.3` | Paper |
| `runPurpur-1.21.4`, `runPurpur-1.21.11`, `runPurpur-26.1`, `runPurpur-26.3` | Purpur |

**In IntelliJ:** open the **Gradle** tool window (the elephant icon on the right edge) → `dkBank` →
`plugin` → `Tasks` → **`dkbank test servers`**, then double-click a task. If the group isn't there, click
the **Reload All Gradle Projects** button (circular arrows) at the top of the Gradle window.

**From a terminal** (Windows): `.\gradlew.bat :plugin:runPaper-1.21.4`
(macOS/Linux: `./gradlew :plugin:runPaper-1.21.4`)

The first run of each server stops to ask you to accept the Minecraft EULA: set `eula=true` in
`run/<server>/eula.txt` and run the task again. Stop a server by typing `stop` in its console.

## Commands (0.5.0)

With `menus.open-from-commands: true` (the default), `/bank`, `/bank deposit`, `/bank withdraw`,
`/bank pay`, `/bank tiers` and `/bank history` open menus when typed on their own. The menus are in
`plugins/dkBank/menus/`.


| Command | Permission (default) |
|---|---|
| `/bank`, `/bank menu` | `dkbank.use` (everyone): the bank menu |
| `/bank balance` | `dkbank.use` (everyone): balance in chat |
| `/bank deposit <amount>` | `dkbank.deposit` (everyone) |
| `/bank withdraw <amount>` | `dkbank.withdraw` (everyone) |
| `/bank pay <player> <amount>` | `dkbank.pay` (everyone) |
| `/bank history [page]` | `dkbank.history` (everyone) |
| `/bank interest` | `dkbank.interest` (everyone) |
| `/bank tiers` | `dkbank.tiers` (everyone) |
| `/bank top [page]` | `dkbank.top` (everyone) |
| `/bank upgrade [confirm]` | `dkbank.upgrade` (everyone) |
| `/bank balance <player>` | `dkbank.balance.others` (op) |
| `/bank admin give\|take\|set <player> <amount>` | `dkbank.admin.give` / `.take` / `.set` (op) |
| `/bank admin history <player> [page]` | `dkbank.admin.history` (op) |
| `/bank admin tier <player> <tier\|default>` | `dkbank.admin.tier` (op) |
| `/bank admin alts <player> [allow\|reset]` | `dkbank.admin.alts` (op) |
| `/bank admin economy [days]` | `dkbank.admin.economy` (op) |
| *(never locked by the alt limit)* | `dkbank.alts.bypass` (op) |
| *(a tier from a rank)* | `dkbank.tier.<name>`, e.g. `dkbank.tier.gold` (nobody, not even ops) |
| `/bank admin reload` | `dkbank.admin.reload` (op) |

Amounts: `1000`, `1,000`, `1.5k`, `2m`, `1b`, `all`, `half`. `dkbank.admin` gives every admin permission.

## Releasing

1. Set `version` in `gradle.properties`.
2. Run `./gradlew build` and test on the servers above.
3. Upload `plugin/build/libs/dkBank-<version>.jar` to the marketplaces.

To publish the Developer API to your local Maven repository: `./gradlew :api:publishToMavenLocal`.

## Placeholders (PlaceholderAPI)

All values come from memory, so scoreboards and tab lists can use them as often as they like.

| Placeholder | Shows |
|---|---|
| `%dkbank_balance%` | Bank balance, formatted like the rest of dkBank |
| `%dkbank_balance_short%` / `_full` / `_raw` | `$1.2M` / `$1,234,567.89` / `1234567.89` |
| `%dkbank_max_balance%`, `%dkbank_room%` | Tier's maximum balance, space left |
| `%dkbank_tier%`, `%dkbank_tier_plain%`, `%dkbank_tier_id%` | Tier name in colour, without colour, its id |
| `%dkbank_next_tier%`, `%dkbank_next_tier_cost%` | The tier `/bank upgrade` buys, and its price |
| `%dkbank_online_rate%`, `%dkbank_offline_rate%`, `%dkbank_cap%` | Interest rates (percent) and cap |
| `%dkbank_next_payout%`, `%dkbank_next_payout_seconds%` | Playtime until the next payout |
| `%dkbank_locked%` | `true` if the alt-account limit locks the bank |
| `%dkbank_rank%` | Place on the leaderboard (`-` if not on it) |
| `%dkbank_top_name_<n>%`, `%dkbank_top_balance_<n>%`, `%dkbank_top_balance_short_<n>%` | Leaderboard place n |
| `%dkbank_total%`, `%dkbank_total_short%`, `%dkbank_accounts%` | All banks together, number of accounts |

