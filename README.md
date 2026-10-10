# dkBank

A premium bank plugin for Paper and Purpur: interest while playing and while away, bank tiers, chest
menus, a leaderboard, an alt-account limit and a developer API.

> Proprietary software. See [LICENSE](LICENSE). The Developer API in `api/` is MIT-licensed.

| | |
|---|---|
| Version | 1.1.0 |
| Servers | Paper, Purpur |
| Minecraft | 1.21.4 – 26.3 (one jar) |
| Java | 21 or newer (1.21.x servers run 21, 26.x servers run 25) |
| Needs | Vault (or VaultUnlocked) and an economy plugin |
| Author | direk james |
| Works with | dkCore (optional): part of the dk suite, uses dkAFK for interest |

## Documentation

Server owners: [docs/](docs/README.md) — installation, interest, every setting, tiers, menus, commands
and permissions, placeholders, the alt-account limit, FAQ.

Developers: [api/README.md](api/README.md) and the example addon in `example/`.

## Project layout

```
api/       Developer API (MIT): what other plugins compile against
plugin/    the plugin itself (proprietary): built into dkBank-<version>.jar
example/   a small addon using the API (not shipped)
docs/      documentation for server owners
sales/     store page text and the release checklist
gradle/libs.versions.toml   every dependency version in one place
```

Packages: `dev.direk.dkbank` (plugin), `dev.direk.dkbank.api` (API).

## dk suite (dkCore)

dkBank is a standalone plugin and never needs dkCore, so buyers can use it on any server. When
[dkCore](https://github.com/direkjames/dkcore) is installed, dkCore links to dkBank on its own:

- every dk plugin can use the bank through dkCore's `bank()` service, without depending on dkBank
- dkAFK's AFK status is passed to dkBank through the `AfkSource` API, so AFK players earn the offline
  interest rate with no placeholders needed
- dkBank shows in dkCore's "dk suite ready" summary

Never add dkCore to dkBank's `depend` or `softdepend`: dkCore lists dkBank as a softdepend, and listing
it back would make a loading loop.

## Building

Open the folder in IntelliJ IDEA and let Gradle sync. If you don't have JDK 21, Gradle downloads it.

```
./gradlew build
```

The plugin jar is `plugin/build/libs/dkBank-<version>.jar` (the only jar there). Bundled libraries (HikariCP, bStats) are moved into
`dev.direk.dkbank.libs` inside the jar so they can't clash with other plugins.

dkCore compiles against the dkBank API. After changing anything in `api/`, run
`./gradlew :api:publishToMavenLocal` so dkCore picks it up.

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

## Testing and releasing

- [TESTING.md](TESTING.md): automatic tests, crash and load test results, the optional MySQL tests,
  and the checklist for a real server.
- [sales/RELEASE.md](sales/RELEASE.md): steps for publishing a version.
- [CHANGELOG.md](CHANGELOG.md): what changed in each version.
