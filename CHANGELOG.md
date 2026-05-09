# Changelog

All notable changes to **PlayerSync** are documented here.

---

## [2.1.5] - 2026-04-22 (cont.)

### Added (Phase 8: configs + admin commands)

- **Structured config sections** — `connection`, `general`, `save_triggers`, `sync_toggles`, `performance`, `safety`, `observability`. Old keys still accepted thanks to NeoForge's lenient loader.
- **Sync toggles** — `sync_inventory`, `sync_ender_chest`, `sync_xp`, `sync_effects`, `sync_health_food`, `sync_curios`, `sync_accessories`, `sync_backpacks`, `sync_cosmetic_armor`, `sync_refined_storage`. All default true. Wired as restore-side guards in each mod-compat path.
- **Save triggers** — `save_on_death` (default true), `save_on_respawn` (default true). `save_on_dimension_change` kept from Phase 4.
- **Perf configs** — `heartbeat_interval_seconds` (default 30), `peer_stale_threshold_seconds` (default 60), `join_poll_max_attempts` (default 120), `join_poll_interval_ms` (default 500), `pool_stats_interval_minutes` (default 5, 0 to disable), `hikari_pool_max_size` (default 15), `hikari_leak_threshold_ms` (default 25000).
- **Safety configs** — `refuse_empty_inventory_write` (default true) now enforced inside `writeSnapshotToDB`: if the snapshot inventory is empty/tiny AND the DB row currently has real data, the write is refused and logged as `DATA_LOSS`. `max_inventory_size_bytes` (default 10 MB) rejects oversized snapshots. `skip_saves_when_tps_below` placeholder for future use. `kick_message`, `kick_grace_period_ms`.
- **Observability configs** — `log_structured_json` (future), `log_rotation_size_mb`, `log_rotation_max_files`.
- **Admin commands — `/playersync`** — full toolkit for diagnosis and maintenance:
  - `status` — server id, heartbeat age, executor + Hikari pool snapshot, online count
  - `poolstats` — immediate log of current pool stats
  - `flush [player]` — force save of all online players or a specific one
  - `info <player>` — DB row metadata (last_server, online flag, data sizes)
  - `dump <player>` — full DB row dump into server log
  - `resync <player>` — clear player_synced tag and kick to force fresh restore
  - `wipe <player> confirm` — DANGER: DELETE all rows for a player
  - `orphans` — list online=1 rows whose peer is dead/stale
  - `clearorphans [server_id]` — clear orphaned online flags
  - `peers` — list all peer servers with their heartbeat age and ALIVE/STALE/STOPPED tag
  - `peerkill <server_id>` — force-disable a zombie peer
  - `cleanup` — one-shot orphans + stale peers cleanup
  - `reload` — status note about runtime config reload
  - `help` — in-chat command reference
- All commands require permission level 2 (op) and log to `SyncLogger` as `ADMIN_*` events for audit trail.

### Changed

- `JDBCsetUp.executePreparedUpdate` now delegates to `executePreparedUpdateRet` which returns rows affected. Existing callers unchanged; admin commands use the ret version for meaningful counts.
- `HeartbeatService` + `PoolStatsReporter` + `doPlayerJoin` poll all read their interval/threshold from the new config keys instead of hardcoded constants.

### Ajouts (French mirror — Phase 8)

- **Sections config structurées** — `connection`, `general`, `save_triggers`, `sync_toggles`, `performance`, `safety`, `observability`.
- **Toggles de sync** — 10 clés pour activer/désactiver la sync par catégorie.
- **Triggers de sauvegarde** — `save_on_death`, `save_on_respawn`, `save_on_dimension_change`.
- **Configs perf** — intervalles heartbeat/poll/pool-stats/hikari, seuils peer-stale.
- **Configs sécurité** — `refuse_empty_inventory_write` (enforce-wipe protection), `max_inventory_size_bytes` (anti-bloat), `kick_message`, `kick_grace_period_ms`.
- **Commandes admin `/playersync`** — 14 commandes pour diagnostic et maintenance (status, flush, info, dump, resync, wipe, orphans, clearorphans, peers, peerkill, cleanup, poolstats, reload, help).
- Toutes les commandes requièrent permission op (niveau 2) et logguent dans `SyncLogger` pour traçabilité.

---

## [2.1.5] - 2026-04-22

### Fixed (English first)

- **Critical item duplication on drop + quick disconnect + reconnect** — Race condition between the auto-save background task and the logout background task could commit a stale snapshot AFTER the logout save, resurrecting dropped items. Triple guard now applied: `pendingLogoutSaves` check (early + under lock) and `SELECT online FROM player_data` skip if logout already committed. Logout BG now acquires `bgLock` with blocking `.lock()` for proper serialization.
- **Backpack / Sophisticated Storage merge-on-restore duplication** — `setBackpackContents` / `setStorageContents` upstream are shallow merges, not replaces. Restore now calls `removeBackpackContents` / `removeStorageContents` (with reflection fallback if absent) AND passes a defensive NBT copy. Fixes mass-duplication of items in backpacks/shulkers on every cross-server transfer.
- **Cross-server save overwrite** — When `writeSnapshotToDB`'s `last_server` guard blocked the core player_data UPDATE, the downstream backpack/SS/RS2 saves still executed and overwrote the claiming server's data. The function now returns a boolean; all 5 callers short-circuit downstream writes on guard block.
- **30-second join delay on zombie peer servers** — `doPlayerJoin` poll waited the full 60 attempts (30s) for server_ids that no longer existed (legacy `server_id=0` rows, or peers that crashed without clearing `online=0`). New `isPeerServerStale` check (peer_id=0 OR heartbeat >60s) takes over immediately and force-clears the orphaned flag. Poll max raised from 60 to 120 attempts (60s) for legitimate slow shutdowns.
- **Curios wipe on dead player** — Legacy `StoreCurios` wrote an empty flatMap when the Curios capability was unavailable, wiping DB data. Now early-returns with a WARN log.

### Added

- **JVM shutdown hook (kill -9 / OOM / SIGTERM recovery)** — New `CrashRecovery.installShutdownHook` registers a non-daemon hook that calls `VanillaSync.emergencyFlushAll` synchronously to snapshot and write every online player before process exit. Marks `server_info.enable=0` so peers detect the shutdown.
- **Startup orphan-flag recovery** — `CrashRecovery.clearOrphanedOnlineFlags` runs at `onServerStarting` to clear any `player_data.online=1` rows left by a previous ungraceful exit. Logs the count via `SyncLogger`.
- **Zombie-peer reporter** — `CrashRecovery.reportZombiePeers` logs peer `server_id`s whose heartbeat is stale or missing at boot time.
- **Server heartbeat service** — `HeartbeatService` pings `server_info.last_update` every 10 seconds so peer servers can distinguish live from dead via the new `isPeerServerStale` check.
- **Periodic full-save scheduler** — `PeriodicSaveService` triggers a complete save (player data + backpacks + SS + RS2) for every online synced player every `auto_save_interval_minutes` (new config, default 10, range 0-1440). Independent of NeoForge's vanilla `PlayerEvent.SaveToFile` cadence.
- **Dimension-change save trigger** — New `onPlayerChangeDimension` handler, gated by `save_on_dimension_change` config (default false). Protects against mid-teleport crashes.
- **Executor + HikariCP pool stats reporter** — `PoolStatsReporter` logs `[POOL] executor active/queue/idle, hikari active/idle` every 5 minutes. WARN thresholds trigger when queue >400/512 or Hikari active >=14/15.
- **Structured logging events** — `SyncLogger` gained `containerForceClosed`, `modCompatSkip`, `modCompatSaved`, `modCompatRestored`, `storageSave`, `poolStats`, `warnPlayer`, `nbtAnomaly` for finer-grained diagnostics.

### Changed

- **`writeSnapshotToDB` signature** — Now returns `boolean` instead of `void`. `true` means the core UPDATE persisted, `false` means the `last_server` guard blocked. All callers MUST check the return before firing downstream backpack/SS/RS2 writes.
- **Default `auto_save_interval_minutes`** — 10 min (new config key). Trades data-loss window on crash for DB load. Set to 0 to disable.
- **Backpack / SS restore** — Now uses two-step clear (public API + reflection fallback) and defensive NBT copy before upstream setter. Full log line per restore with `cleared_via=api|reflection` and `nbt_keys=N`.

---

### Correctifs (French mirror)

- **Duplication d'items critique lors d'un drop + déconnexion rapide + reconnexion** — Race condition entre la task auto-save background et la task logout background pouvait commiter un snapshot périmé APRÈS le save logout, ressuscitant les items drop. Triple garde maintenant appliquée : check `pendingLogoutSaves` (early + sous lock) et skip via `SELECT online FROM player_data` si le logout a déjà commité. La task logout BG acquiert maintenant `bgLock` en blocking `.lock()` pour sérialiser proprement.
- **Duplication Backpack / Sophisticated Storage par merge au restore** — `setBackpackContents` / `setStorageContents` en amont sont des merges shallow, pas des replaces. Le restore appelle maintenant `removeBackpackContents` / `removeStorageContents` (avec fallback reflection si absent) ET passe une copie défensive du NBT. Corrige la duplication massive d'items dans les backpacks/shulkers à chaque transfert cross-server.
- **Écrasement cross-server des saves** — Quand le guard `last_server` de `writeSnapshotToDB` bloquait l'UPDATE core player_data, les saves downstream backpack/SS/RS2 s'exécutaient quand même et écrasaient les données du serveur ayant claim. La fonction retourne maintenant un boolean ; les 5 callers court-circuitent les writes downstream en cas de guard block.
- **Délai de 30 secondes à la connexion sur serveurs zombies** — Le poll `doPlayerJoin` attendait les 60 tentatives (30s) pour des `server_id` n'existant plus (lignes legacy `server_id=0`, ou peers ayant crashé sans clear `online=0`). Nouveau check `isPeerServerStale` (peer_id=0 OU heartbeat >60s) prend la main immédiatement et force-clear le flag orphelin. Poll max passé de 60 à 120 tentatives (60s) pour couvrir les shutdowns lents légitimes.
- **Wipe Curios sur joueur mort** — La méthode legacy `StoreCurios` écrivait un flatMap vide quand la capability Curios était absente, wipant les données DB. Elle early-return maintenant avec un log WARN.

### Ajouts (French mirror)

- **Hook JVM shutdown (kill -9 / OOM / SIGTERM recovery)** — Nouveau `CrashRecovery.installShutdownHook` enregistre un hook non-daemon qui appelle `VanillaSync.emergencyFlushAll` synchronement pour snapshot et écrire chaque joueur online avant la fin du process. Marque `server_info.enable=0` pour que les peers détectent le shutdown.
- **Recovery des flags orphelins au boot** — `CrashRecovery.clearOrphanedOnlineFlags` tourne au `onServerStarting` pour clear les rows `player_data.online=1` laissées par une sortie ungracieuse précédente. Log le compte via `SyncLogger`.
- **Reporter de peers zombies** — `CrashRecovery.reportZombiePeers` log les `server_id` peers dont le heartbeat est stale ou absent au boot.
- **Service heartbeat** — `HeartbeatService` ping `server_info.last_update` toutes les 10 secondes pour que les peers distinguent live vs dead via le nouveau check `isPeerServerStale`.
- **Scheduler de sauvegarde périodique** — `PeriodicSaveService` déclenche une save complète (player data + backpacks + SS + RS2) pour chaque joueur online synced toutes les `auto_save_interval_minutes` (nouvelle config, défaut 10, plage 0-1440). Indépendant de la cadence vanilla `PlayerEvent.SaveToFile` de NeoForge.
- **Trigger save sur changement de dimension** — Nouveau handler `onPlayerChangeDimension`, gated par la config `save_on_dimension_change` (défaut false). Protège contre les crashes en plein téléport.
- **Reporter stats executor + HikariCP** — `PoolStatsReporter` log `[POOL] executor active/queue/idle, hikari active/idle` toutes les 5 min. Seuils WARN quand queue >400/512 ou Hikari active >=14/15.
- **Événements structurés** — `SyncLogger` a gagné `containerForceClosed`, `modCompatSkip`, `modCompatSaved`, `modCompatRestored`, `storageSave`, `poolStats`, `warnPlayer`, `nbtAnomaly` pour un diagnostic plus fin.

### Modifications

- **Signature `writeSnapshotToDB`** — Retourne maintenant `boolean` au lieu de `void`. `true` = l'UPDATE core a persisté, `false` = le guard `last_server` a bloqué. Tous les callers DOIVENT vérifier le retour avant de déclencher les writes downstream backpack/SS/RS2.
- **Défaut `auto_save_interval_minutes`** — 10 min (nouvelle clé config). Trade-off entre fenêtre de perte de données sur crash et charge DB. 0 pour désactiver.
- **Restore Backpack / SS** — Utilise maintenant un clear en deux étapes (API publique + fallback reflection) et une copie défensive NBT avant le setter upstream. Log complet par restore avec `cleared_via=api|reflection` et `nbt_keys=N`.

---
