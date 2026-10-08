# dkBank Roadmap

A premium bank plugin for Paper and Purpur: a bank balance separate from the wallet, with separate
online and offline interest rates. Closed source, with a public Developer API.

Status legend: ☐ planned · ◐ in progress · ☑ done

---

## Decisions

| Topic | Decision | Why |
|---|---|---|
| Name | **dkBank** | Checked free on BuiltByBit, SpigotMC and Voxel.Shop |
| Servers | **Paper and Purpur** | Purpur is a Paper fork, so one build covers both. Folia later |
| Minecraft | **1.21.4 up to 26.3**, one jar | Covers most active servers |
| Java | Compiled for **Java 21** | 1.21.x servers run Java 21, 26.x servers run Java 25. Java 21 code runs on both |
| API we compile against | **paper-api 1.21.4** | The oldest supported version. Anything it has also exists on newer servers |
| Economy | **Vault API** | Works with Vault and VaultUnlocked, and with any economy plugin that supports them |
| Money | **BigDecimal**, never `double` | No rounding drift, e.g. 0.1 + 0.2 is exactly 0.3 |
| Storage | **SQLite** (default), **MySQL / MariaDB** (networks) | Through HikariCP, all queries off the main thread |
| Libraries | HikariCP **shaded and relocated** into the jar | Works on servers without internet access, and can't clash with other plugins |
| Packages | `dev.direk.dkbank` (plugin), `dev.direk.dkbank.api` (API) | |
| Null safety | JSpecify `@NullMarked` | Paper's own standard; the JetBrains annotations aren't available to plugins on 1.21.4 |
| Licenses | Proprietary (plugin), MIT (API) | Done |

---

## Phase 0 · Project setup — `0.0.x` ☑

- ☑ Gradle 9.8 multi-module project: `api` (MIT, published for developers) and `plugin` (proprietary)
- ☑ Java 21 toolchain, paper-api 1.21.4, Vault API, PlaceholderAPI (soft), versions in `gradle/libs.versions.toml`
- ☑ HikariCP shaded and relocated to `dev.direk.dkbank.libs.hikari`
- ☑ Test server tasks for Paper and Purpur **1.21.4, 1.21.11, 26.1 and 26.3**, each with the right Java
- ☑ JUnit 6, with a setup test guarding plugin.yml and the Java 21 class format
- ☑ GitHub Actions: build and test on every push, jar attached to each run
- ☑ API entry point `DkBankAPI.get()`, registered as a Bukkit service

## Phase 1 · Core banking — `0.1.0`

- ☐ Accounts created automatically on first join
- ☐ Deposit (wallet → bank), withdraw (bank → wallet), transfer (bank → another player's bank)
- ☐ Amounts: `1000`, `1.5k`, `2m`, `1b`, `all`, `half`
- ☐ Optional fees on withdrawals and transfers, minimum and maximum amounts
- ☐ All-or-nothing transfers: if the database write fails, the wallet is refunded
- ☐ One lock per account, so two actions can never spend the same money
- ☐ Transaction log (type, amount, balance after, time, other party)
- ☐ Commands with tab completion: `/bank`, `deposit`, `withdraw`, `pay`, `balance`, `history`
- ☐ Admin: `/bank admin give | take | set | balance | history <player>`
- ☐ `messages.yml` in MiniMessage, configurable prefix

## Phase 2 · Interest engine — `0.2.0`

The core feature. Interest is calculated from timestamps, so it's exact, restarts and crashes never
double-pay or skip a payout, and offline accounts cost nothing.

- ☐ **Online interest:** paid every `online.interval` (e.g. hourly) to active players
- ☐ **Offline interest:** builds up while away, settled at login with a "while you were away" summary
- ☐ Separate rates, compounding, configurable payout messages and sounds
- ☐ AFK players earn the offline rate. Hooks: EssentialsX, CMI, DirekAntiAFK, plus a built-in idle check
- ☐ Anti-abuse:
  - money must stay deposited for `min-deposit-age` before it earns
  - offline interest stops after `offline.max-days`
  - only the balance up to the tier's interest cap earns
  - maximum interest per payout
- ☐ Unit tests, including a simulated year of interest to prove the limits hold
- ☐ Default rates: **to confirm** (proposal: online 1% per hour played, offline 2% per day, capped per tier)

## Phase 3 · Bank tiers — `0.3.0`

- ☐ Tiers in `tiers.yml`: name, icon, upgrade cost, online and offline rate, interest cap, max balance
- ☐ Upgrades bought with in-game money (takes money out of the economy)
- ☐ Permission tiers (`dkbank.tier.<name>`) for ranks and donor perks; the higher tier wins
- ☐ Confirmation before paying for an upgrade

## Phase 4 · GUIs — `0.4.0`

- ☐ Main menu: balance, wallet, tier, current rates, next payout, quick actions
- ☐ Deposit and withdraw menus with preset amounts (configurable) and a custom amount
- ☐ Custom amount input by chat prompt (works on every supported version)
- ☐ Transfer menu, upgrades menu, paged history
- ☐ Every menu editable in `menus/*.yml`: layout, items, names, lore, sounds
- ☐ Premium touches: click sounds, balance animations on deposit, fill and border items, consistent theme
- ☐ Works with Bedrock players through Geyser (chest menus are supported)

## Phase 5 · Integrations and admin tools — `0.5.0`

- ☐ PlaceholderAPI: balance, formatted balance, tier, online/offline rate, next payout, rank on `/bank top`
- ☐ `/bank top` leaderboard, cached so it never queries the database per view
- ☐ Economy report: total in banks, interest created per day (inflation watch), top earners
- ☐ Number formatting options: `1,234.56`, `1.2k`, currency symbol and position
- ☐ Import from other bank plugins (to confirm which: e.g. BankPlus) — a strong selling point

## Phase 6 · Developer API — `0.6.0`

- ☐ `DkBankAPI` service: read balances and tiers, deposit, withdraw, transfer, all returning futures
- ☐ Events: `BankDepositEvent`, `BankWithdrawEvent`, `BankTransferEvent`, `BankInterestEvent`,
  `BankTierChangeEvent` (cancellable before, informational after)
- ☐ Javadocs, and a published `dkBank-API` artifact
- ☐ Small example addon showing how to use the API

## Phase 7 · Hardening (beta) — `0.9.0`

- ☐ Full test pass on Paper and Purpur **1.21.4, 1.21.11, 26.1, 26.3**, SQLite and MySQL
- ☐ Load test: 10,000 accounts, 200 online players, payouts and leaderboard under a spark profile
- ☐ Crash test: kill the server mid-transaction, confirm no money is lost or duplicated
- ☐ Exploit test: double clicks, two transfers at once, logging out mid-transaction, economy plugin offline
- ☐ Config validation with clear console warnings, and automatic addition of new settings on update
- ☐ Run on our own server for a while before selling

## Phase 8 · Release — `1.0.0`

- ☐ Documentation: install guide, every config option, permissions, placeholders, API
- ☐ Sales pages for BuiltByBit, SpigotMC and Voxel.Shop: screenshots, GIFs, feature list
- ☐ Support channel (e.g. Discord) and a bug report template
- ☐ Update checker (optional bStats for usage numbers)

---

## Later (after 1.0)

- Folia support
- Code obfuscation (Developer API left readable)
- Bedrock forms through Floodgate
- Multiple currencies (VaultUnlocked)
- Bank cheques, savings goals, team or island accounts, loans
- Investment shares (planned earlier, set aside for now)

## Open questions

1. Default interest rates (see Phase 2)
2. Which bank plugins to support importing from (see Phase 5)
3. GUI theme or colors you'd like dkBank to be known for
