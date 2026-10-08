# FAQ and troubleshooting

**"The bank is unavailable: no economy plugin was found."**
Install Vault (or VaultUnlocked) and an economy plugin such as EssentialsX. The console says which is
missing at startup.

**dkBank didn't start and says a file "has a mistake".**
A YAML error, usually a tab instead of spaces or a missing quote. The console names the file and line;
[yamlchecker.com](https://yamlchecker.com) helps. dkBank refuses to start rather than use wrong
settings, and never overwrites your file.

**A setting doesn't do anything.**
Check the console for "Unknown setting ... (a typo?)". Then `/bank admin reload`.

**Players aren't getting interest.**
- Is their bank balance above zero, and has a full period passed? `/bank interest` shows progress.
- Money deposited during a cycle starts earning at the next payout (the lowest-balance rule).
- Are they locked by the alt limit? `/bank admin alts <player>`.
- Is the tier's `cap` or `online-rate` 0?

**Everyone is locked by the alt limit.**
Your proxy doesn't forward IP addresses. Turn on IP forwarding (Velocity `player-info-forwarding-mode`,
BungeeCord `ip_forward`), or set `alt-limit.enabled: false`.

**I changed tiers.yml or a menu but nothing changed.**
Run `/bank admin reload`. If a file has a mistake, the previous version is kept and the console says why.

**My menu file is missing a new button after updating.**
Menu files are never changed after they're created, so your edits are safe. Delete the file and
reload to get the new default.

**Can players lose money if the server crashes?**
No. Every change is one database transaction with its history line, so it either fully happens or not
at all. dkBank was crash-tested by killing it repeatedly mid-transaction with every cent accounted for.

**Support:** run `/bank admin info` and include its output, your server version and the console error
(if any) in your report.
