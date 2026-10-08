# Alt-account limit

Players sometimes make extra accounts to farm interest or starting money. dkBank lets only the first
`max-accounts` (default 3) accounts that play from one internet connection use the bank. Later ones are
**locked**: no deposits, transfers, upgrades or interest. With `locked-can-withdraw: true` they can
still take out money they already had.

- Only logins in the last `remember` (default 30 days) count, so someone who moved away stops counting.
- Locked players are told why when they join, and the main menu shows a "Bank locked" item.
- `/bank admin alts <player>` lists the accounts on that player's latest connection and their status.
- `/bank admin alts <player> allow` lets an account use the bank anyway (siblings, roommates). Allowed
  accounts don't take one of the places. `reset` undoes it.
- `dkbank.alts.bypass` (operators by default) is never locked.

**Privacy:** addresses are stored as salted one-way hashes, never the address itself, and forgotten
after `remember`.

**Proxies and Bedrock:** behind Velocity or BungeeCord, IP forwarding must be on; Bedrock players need
Floodgate passing their real address. Local addresses (127.0.0.1, 192.168.x.x) are never locked, and
the console warns when players join from one.
