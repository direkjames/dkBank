# dkBank Developer API

The API is MIT licensed (see `LICENSE` in this folder), unlike dkBank itself. Use it to read and
change bank balances and tiers, and to react to what happens in the bank.

## Add it to your plugin

The API classes are inside the dkBank jar on the server. Your plugin only compiles against them
(`compileOnly`), and must not include them in its own jar.

**Gradle (Kotlin DSL)** — with the API jar (`dkBank-API-<version>.jar`) in a `libs` folder:

```kotlin
dependencies {
    compileOnly(files("libs/dkBank-API-1.0.0.jar"))
}
```

Or, after `./gradlew :api:publishToMavenLocal` in the dkBank project:

```kotlin
repositories { mavenLocal() }
dependencies { compileOnly("dev.direk:dkBank-API:1.0.0") }
```

**plugin.yml**

```yaml
depend: [dkBank]        # or softdepend, if dkBank is optional for you
```

## Use it

```java
DkBankAPI bank = DkBankAPI.get();

// Add 500 to a player's bank (a reward). The future completes on the main thread.
bank.give(player.getUniqueId(), new BigDecimal("500"), "MyPlugin").thenAccept(result -> {
    if (result.success()) player.sendMessage("Reward added! Bank: " + bank.format(result.balance()));
    else player.sendMessage("Couldn't add it: " + result.failure());
});

// Read from memory (online players), no waiting:
Optional<BigDecimal> balance = bank.cachedBalance(player.getUniqueId());
BankTier tier = bank.tierOf(player);
int place = bank.rank(player.getUniqueId());
```

| Method | What it does |
|---|---|
| `account(uuid)` | Loads an account (balance, name, bought tier) |
| `cachedBalance(uuid)` | Balance of an online player, from memory |
| `has(uuid, amount)` | Whether the bank holds at least that much |
| `give(uuid, amount, source)` | Adds money (respects the tier's maximum balance) |
| `take(uuid, amount, source)` | Removes money (fails if there isn't enough) |
| `transfer(from, to, amount)` | Moves money between banks, no fees |
| `tiers()`, `tier(id)` | The tiers from tiers.yml |
| `tierOf(player)` | An online player's tier, including permission tiers |
| `boughtTier(uuid)`, `setTier(uuid, id, source)` | The tier an account bought; set it for free |
| `isLocked(player)` | Whether the alt-account limit locks their bank |
| `leaderboard()`, `rank(uuid)` | The leaderboard from its last update |
| `format(amount)` | An amount the way dkBank shows money |

**Threads:** methods returning a `CompletableFuture` work in the background and complete on the main
thread. Call them from any thread, but never `join()` or `get()` them on the main thread: that freezes
the server.

## Events

All fired on the main thread.

| Event | When | Cancellable |
|---|---|---|
| `BankPreTransactionEvent` | A player is about to deposit, withdraw or send money | Yes, with an optional message |
| `BankTransactionEvent` | Any bank balance changed (deposits, interest, staff, plugins...) | No |
| `BankUpgradeEvent` | A player is about to buy a tier | Yes, with an optional message |
| `BankTierChangeEvent` | An account's bought tier changed | No |

```java
@EventHandler
public void onDeposit(BankPreTransactionEvent event) {
    if (event.getAction() == BankPreTransactionEvent.Action.DEPOSIT && inCombat(event.getPlayer())) {
        event.setCancelled(true);
        event.setCancelMessage(Component.text("No banking during combat!"));
    }
}
```

The `example` folder of the dkBank project is a complete small addon using all of this.
