# Project Rules & AI/IDE Instructions

Single source of truth for anyone (human or tooling) working on this repository. Read entirely before changing anything.

## 1. Project Identity

| Field        | Value                                                              |
|--------------|--------------------------------------------------------------------|
| Project      | PlayerSync (Team Arcadia fork)                                     |
| Mod ID       | `playersync`                                                       |
| Package      | `vip.fubuki.playersync`                                            |
| Version      | 2.1.5 (VERSION LOCKED — see §3)                                    |
| Tech stack   | Java 21, NeoForge 21.1.x (Minecraft 1.21.1), Gradle (ModDevGradle) |
| Storage      | MySQL / MariaDB via HikariCP                                       |
| Authors      | mlus (upstream), Team Arcadia / vyrriox (fork maintenance)         |
| License      | GPL-3.0                                                            |
| Dependencies | HikariCP, MySQL Connector/J; soft-deps: Curios, Accessories, Cosmetic Armor Reworked, Sophisticated Backpacks/Storage, Refined Storage 2, ReviveMe, Corpse |

## 2. Git Workflow

- Active branches: `Arcadia-Fix` (default, production fixes) and `Arcadia-Dev` (experiments). PRs target `main` upstream only when explicitly requested.
- Commit style: `type: short message` (`fix:`, `perf:`, `docs:`, `chore:`, `audit:`). Iterative bugfix campaigns are suffixed `rN` (see git log: `fix: revive-dup r7 — ...`).
- Push directly to `Arcadia-Fix` after build passes. No force-push.
- NEVER commit: `test-procedures/`, `TEST_PROCEDURE_*.html`, `.claude/`, `CLAUDE.md`, `run/`, `build/`.

## 3. Code Conventions

- **Language**: all code, comments, log messages in English. User-facing docs (README/CHANGELOG) bilingual EN+FR.
- **Version lock**: NEVER bump `mod_version` (gradle.properties), CHANGELOG version headers, or any other version number unless explicitly instructed.
- **Naming**: standard Java (`PascalCase` classes, `camelCase` members). Existing files keep their historical style.
- **SQL**: ONLY the `executePrepared*` / `update` / `executeBatchTransaction` helpers with `?` placeholders for anything carrying data. Plain-string `executeUpdate(String)` is for startup DDL with validated identifiers only. Never reintroduce format-string SQL helpers.
- **Threading model (critical)**: entity reads/writes ONLY on the server main thread; DB I/O ONLY on `VanillaSync.executorService` (or the network thread for `PlayerNegotiationEvent`). The established pattern is snapshot-on-main → write-on-executor (`DeferredPlayerSnapshot.materialize()` runs on BG). Never block the main thread on JDBC — including Brigadier command handlers.
- **What NOT to do**:
  - No behavioral heuristics (HP thresholds, effect signatures) to gate destructive DB actions — explicit signals only (ERROR_LOG 2026-05-20).
  - `@SubscribeEvent(receiveCanceled = true)` is silently non-functional on NeoForge bus 8.x for `ICancellableEvent` — use programmatic `EVENT_BUS.addListener(priority, true, ...)` if canceled events are needed.
  - Any item-clearing DB logic MUST check the `keepInventory` game rule.
  - Never write an "empty" state to DB when the source is uncertain (absent capability ≠ empty data).
  - Always clear-before-restore for stores that merge instead of replace (BackpackStorage, SS contents).

## 4. Project Structure

```
src/main/java/vip/fubuki/playersync/
├── PlayerSync.java          # Mod entry: DB/DDL init, migrations, services startup
├── CommandInit.java         # /playersync admin commands (DB bodies run async on the executor)
├── config/JdbcConfig.java   # All config keys (general / save_triggers / sync_toggles / performance / safety / observability)
├── sync/
│   ├── VanillaSync.java     # CORE: join/logout/death/save pipeline, executor, guards, snapshots
│   └── addons/
│       ├── ModsSupport.java     # Curios + Sophisticated Backpacks/Storage + RS2 save/restore
│       ├── ModCompatSync.java   # Accessories / CosmeticArmor / NeoForge attachments
│       └── CuriosCache.java     # Death-time curios snapshot cache
└── util/
    ├── JDBCsetUp.java       # HikariCP pool + query helpers + batch transactions
    ├── Tables.java          # Table-name prefix resolution (validated)
    ├── SyncLogger.java      # Async file logger (logs/playersync/sync.log, rotated)
    ├── CrashRecovery.java   # JVM shutdown hook + orphaned-flag cleanup at boot
    ├── HeartbeatService.java / PeriodicSaveService.java / PoolStatsReporter.java
    ├── LocalJsonUtil.java   # Map<->string codecs for the legacy storage format
    └── PSThreadPoolFactory.java
docs/                        # Design notes
ERROR_LOG.md                 # MANDATORY reading: every past bug + prevention rule
CHANGELOG.md                 # Bilingual, strict template, most recent on top
```

## 5. Adding a New Feature (Step by Step)

1. Read `ERROR_LOG.md` entirely — check whether a prevention rule constrains your approach.
2. Map which save/restore paths your change touches (join / logout / death / auto-save / periodic / shutdown / emergency-flush) — a guard added to one path usually must exist in all of them.
3. Implement following the threading model (§3). New DB columns/tables/indexes go through idempotent `INFORMATION_SCHEMA`-checked migrations in `PlayerSync.onServerStartingUnchecked`.
4. `.\gradlew.bat compileJava` must pass with no new warnings.
5. Update `CHANGELOG.md` (EN first, FR mirror) — do NOT bump the version.
6. If a bug was found and fixed along the way, log it in `ERROR_LOG.md` with a prevention rule.
7. Commit `type: message` and push to `Arcadia-Fix`.

## 6. Testing Checklist

- [ ] `.\gradlew.bat compileJava` passes
- [ ] Single-server: join (new player + existing), logout, rejoin — data intact
- [ ] Drop item + instant disconnect + reconnect — no dup (ERROR_LOG 2026-04-22)
- [ ] Death with corpse mod + disconnect from ReviveMe fallen state (`dieOnDisconnect` both true and false) — no dup, no loss (r1-r7)
- [ ] `keepInventory=true` death + disconnect — items preserved
- [ ] Cross-server transfer: backpack / curios / accessories / attachments follow, no dup
- [ ] `kill -9` the server — `CrashRecovery` flushes; rejoin on peer works without 60s wait
- [ ] `/playersync status|info|inventory|orphans` respond without tick stall on a slow DB
- [ ] Non-op in a command block CANNOT run `wipe`/`peerkill`/`cleanup`/`clearorphans`/`resync`
- [ ] sync.log rotates past the configured size

## 7. Environment Setup

```bash
git clone <fork-url> && cd player-sync
docker compose up -d              # MariaDB + Adminer (localhost:3306 / :8080)
.\gradlew.bat compileJava         # build check
.\gradlew.bat runServer           # dev dedicated server (configure config/playersync-common.toml)
```
- IDE: import as Gradle project, JDK 21. NeoForge sources attach via ModDevGradle automatically.
- Windows note: never edit source files with PS 5.1 `Get-Content`/`Set-Content` — it corrupts UTF-8 and adds a BOM (ERROR_LOG 2026-06-09).

## 8. AI Assistant Instructions

1. Read `ERROR_LOG.md` and this file before any change; its prevention rules override default instincts.
2. Respect the version lock (§3) and the bilingual CHANGELOG template.
3. Never weaken an anti-duplication guard (`last_server` CAS, `pendingLogoutSaves`, `bgLock`, online-flag check, hash-skip invalidation, fallen-state detection) without proving every interleaving safe in BOTH handler orders.
4. Keep the threading model intact: no JDBC on the main thread, no entity access off it.
5. Destructive operations (force-push, mass deletion, DB schema drops) require explicit human confirmation.
6. When fixing mod-interaction bugs, decompile the other mod's actual handlers (`javap -p -c`) instead of guessing its event semantics.
7. Test or compile before claiming success; state explicitly when something could not be verified.
