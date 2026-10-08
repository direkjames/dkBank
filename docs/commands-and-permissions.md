# Commands and permissions

`/bank` also works as `/dkbank` (see `command.aliases`). Amounts accept `1000`, `1.5k`, `2m`, `1b`,
`all` and `half`.

## Players (all given by default through `dkbank.use`)

| Command | Permission | |
|---|---|---|
| `/bank`, `/bank menu` | `dkbank.use` | The bank menu (or balance in chat if menus are off) |
| `/bank balance` | `dkbank.use` | Balance in chat |
| `/bank deposit [amount]` | `dkbank.deposit` | Without an amount: the deposit menu |
| `/bank withdraw [amount]` | `dkbank.withdraw` | Without an amount: the withdraw menu |
| `/bank pay [player] [amount]` | `dkbank.pay` | Without arguments: the send-money menu |
| `/bank history [page]` | `dkbank.history` | Without a page: the history menu |
| `/bank interest` | `dkbank.interest` | Rates, what's earning, next payout |
| `/bank tiers` | `dkbank.tiers` | All tiers |
| `/bank upgrade [confirm]` | `dkbank.upgrade` | Buy the next tier |
| `/bank top [page]` | `dkbank.top` | The leaderboard |

## Staff (all through `dkbank.admin`, operators by default)

| Command | Permission | |
|---|---|---|
| `/bank balance <player>`, `/bank admin balance <player>` | `dkbank.balance.others` | |
| `/bank admin give\|take\|set <player> <amount>` | `dkbank.admin.give` / `.take` / `.set` | Logged in their history |
| `/bank admin history <player> [page]` | `dkbank.admin.history` | |
| `/bank admin tier <player> <tier\|default>` | `dkbank.admin.tier` | Set a bought tier for free |
| `/bank admin alts <player> [allow\|reset]` | `dkbank.admin.alts` | Accounts on their connection; allow one anyway |
| `/bank admin economy [days]` | `dkbank.admin.economy` | Money in banks, interest created, top earners |
| `/bank admin info` | `dkbank.admin.info` | Versions and setup, for support requests |
| `/bank admin reload` | `dkbank.admin.reload` | |

## Other permissions

| Permission | Default | |
|---|---|---|
| `dkbank.tier.<name>` | nobody | Gives that tier (ranks, donor perks) |
| `dkbank.alts.bypass` | op | Never locked by the alt-account limit |
