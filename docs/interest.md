# How interest works

Every account earns two kinds of interest, each with its own rate per tier.

## Online interest

Paid for time **played**: the tier's `online-rate` for every `interest.online.period` online (default:
1% per hour). The player gets a message and a sound at each payout. Leaving part-way pays the minutes
played, prorated.

> 10,000 in the bank at 1% per hour: +100 after an hour of play, +25 for a 15-minute visit.

## Offline interest

Paid at **login** for the time away: the tier's `offline-rate` per `interest.offline.period` (default
1% per day), compounding, up to `interest.offline.max-time` away (default 7 days). A "while you were
away" message shows a few seconds after joining.

> 10,000 at 1% per day, away 3 days: +303.01 (the same as three daily payouts). Away a month: only 7
> days count.

## AFK players

Time spent AFK earns the **offline** rate instead of the online one, so AFK farms earn no more than
logging off. A player counts as AFK after `afk-detection.idle-after` without moving, looking around,
chatting or using commands, or when an AFK plugin says so through PlaceholderAPI (EssentialsX, CMI and
DirekAntiAFK are set up by default).

## The lowest-balance rule

Interest is paid on the **lowest balance since the last payout**. Money deposited just before a payout
earns nothing until the payout after it, and withdrawing lowers what earns straight away. This stops
"deposit, collect, withdraw" tricks.

## Caps and limits (per tier)

| Setting | What it does |
|---|---|
| `cap` | Only the balance up to this earns interest. Keeps growth linear instead of snowballing; strongly recommended. |
| `max-per-payout` | Most interest per payout, scaled by the time it covers (logging in and out can't collect it repeatedly). |
| `max-balance` | Interest never takes a bank past its maximum balance. |

## Things to know

- Progress is saved every minute: a crash loses at most a minute, and never pays twice.
- Accounts locked by the [alt-account limit](alt-limit.md) earn no interest.
- `/bank interest` shows a player their rates, what's earning and the next payout.
- `/bank admin economy` shows how much interest is being created, to keep an eye on inflation.
