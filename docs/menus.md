# Menus (menus/*.yml)

Every menu is a file in `plugins/dkBank/menus/`: `main`, `deposit`, `withdraw`, `transfer`, `tiers`,
`confirm-upgrade`, `history` and `top`. Each file's comments list the values and actions it supports.
Run `/bank admin reload` after editing; a file with a mistake keeps its previous version.

## Items

```yaml
items:
  balance:
    slot: 22                 # or slots: [10-16, 20]; 0 is the top left, 9 per row
    material: GOLD_BLOCK     # AIR leaves it empty; values like "<tier-icon>" work
    name: "<gold>Bank balance"
    lore:
      - "<white><balance>"
    glow: true
    amount: 1
    hide-tooltip: false      # true for background panes
    head: viewer             # PLAYER_HEAD: the player's own skin
    action: "open:deposit"   # or actions: [...]
    permission: dkbank.deposit   # hidden without it
```

Text is [MiniMessage](https://docs.advntr.dev/minimessage/format.html), and PlaceholderAPI
placeholders work too. `fill` covers empty slots and `border` the outer ring.

## Custom heads

Copy a head's **Value** from [minecraft-heads.com](https://minecraft-heads.com):

```yaml
  coins:
    slot: 13
    texture: "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUv..."
    name: "<gold>Coins"
```

`material: "head:<Value>"` works too (handy for tier icons in tiers.yml and history items). A
`textures.minecraft.net` link or just the texture id also works.

## Actions

| Action | Where | Does |
|---|---|---|
| `open:<menu>` | all | Opens main, deposit, withdraw, transfer, tiers, history or top |
| `back` | all | The main menu (from the upgrade confirmation: the tiers menu) |
| `close` | all | Closes the menu |
| `command:<command>` | all | Runs a command as the player (`<player>` is their name) |
| `amount:<amount>` | deposit, withdraw | e.g. `amount:all`, `amount:half`, `amount:5k` |
| `custom` | deposit, withdraw | Type an amount in chat |
| `type-name` | transfer | Type a player's name in chat |
| `previous` / `next` | transfer, history, top | Pages |
| `upgrade` | main | Confirm the next tier |
| `confirm` | confirm-upgrade | Buy it |

Opening any menu checks the player's permission for it (e.g. `dkbank.deposit`), whatever the file says.
