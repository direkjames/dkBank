# Tiers (tiers.yml)

Tiers go from lowest to highest. The **first** tier is everyone's starting tier. Players buy the next
one with `/bank upgrade` (or the tiers menu), paid from their bank balance, so upgrades take money out
of the economy. Each upgrade asks for confirmation.

```yaml
tiers:
  silver:
    display-name: "<white>Silver</white>"   # MiniMessage
    icon: IRON_INGOT                        # or "head:<Value from minecraft-heads.com>"
    upgrade-cost: 25000
    buyable: true                           # false = only through a rank permission
    max-balance: 1000000                    # 0 = no limit
    interest:
      online-rate: 1                        # percent per online period; decimals work (0.5, 1.25)
      offline-rate: 1                       # percent per offline period
      cap: 100000                           # only this much earns interest; 0 = no cap (not recommended)
      max-per-payout: 0                     # 0 = no limit
```

The defaults: Basic (free, holds 250K, interest on 25K), Silver (25K), Gold (150K), Platinum (1M),
Diamond (5M), and Black, a rank-only example.

## Tiers from ranks

The permission `dkbank.tier.<name>` (e.g. `dkbank.tier.gold`) gives that tier while the player has it.
A player's tier is the highest of the tier they bought and their permission tiers. These permissions
aren't given to operators automatically. `/bank upgrade` then offers the first buyable tier above it.

## Renaming and removing

The section name (`silver`) is saved in the database. Renaming or removing it moves its players back to
the first tier (the console warns), so change `display-name` instead. `/bank admin tier <player> <tier>`
sets anyone's tier for free; `default` puts them back on the first.
