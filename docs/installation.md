# Installation

## Requirements

| | |
|---|---|
| Server | Paper or Purpur (Spigot isn't supported) |
| Minecraft | 1.21.4 to 26.3, one jar for all |
| Java | 21 or newer (26.x servers need 25) |
| Economy | [Vault](https://www.spigotmc.org/resources/vault.34315/) (or VaultUnlocked) **and** an economy plugin such as EssentialsX or CMI |
| Optional | PlaceholderAPI (placeholders, AFK detection through other plugins) |

## First start

1. Put `dkBank-<version>.jar` in `plugins/`, next to Vault and your economy plugin.
2. Start the server. dkBank creates `plugins/dkBank/` with `config.yml`, `messages.yml`, `tiers.yml`,
   the `menus/` folder and `bank.db` (the SQLite database).
3. Check the console for `Economy: EssentialsX (through Vault)`. If it says Vault or the economy is
   missing, banking is turned off until they're there.
4. Join and type `/bank`.

The defaults are ready to use: 1% interest per hour played and 1% per day away, five buyable tiers
from Basic to Diamond. Look at [tiers.md](tiers.md) and [configuration.md](configuration.md) to make
it fit your economy.

## Updating

Replace the jar and restart. dkBank upgrades its database by itself, and adds new settings (with their
comments) to `config.yml` and `messages.yml` without touching yours. `tiers.yml` and the menu files are
never changed after they're created; to get a new default menu, delete the file and run
`/bank admin reload`.

Always keep a backup of `plugins/dkBank/` (or your MySQL database) before updating.

## MySQL / MariaDB

Use MySQL when several servers share one bank (a network), or if you prefer it. In `config.yml`:

```yaml
storage:
  type: mysql          # or mariadb
  table-prefix: "dkbank_"
  mysql:
    host: "localhost"
    port: 3306
    database: "dkbank"
    username: "dkbank"
    password: "a good password"
    use-ssl: false
    pool-size: 6
```

Create the database first (`CREATE DATABASE dkbank;`) and restart. Every server sharing the bank uses
the same settings. Switching storage type doesn't copy accounts over.

**Networks (Velocity, BungeeCord):** turn on IP forwarding in the proxy, otherwise the alt-account
limit sees every player as the proxy's address (dkBank warns about this and ignores local addresses).
Players can switch servers freely: interest is never paid twice for the same minutes.

## Reloading

`/bank admin reload` reloads `config.yml`, `messages.yml`, `tiers.yml` and the menus. Storage settings
and command aliases need a restart. If a file has a mistake (a tab, a missing quote), nothing is
reloaded and the console says where; at startup, dkBank refuses to start rather than run with the
wrong settings. Your file is never overwritten.
