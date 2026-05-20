# Changelog

All notable changes to **PlayerSync** are documented here.

---

## [Unreleased] - 2026-05-20 (r4)

### Fixed (English first)

- **Item dup on revive-disconnect — mod-slot extension (r4)** — r3 fixed the main inventory / armor / off-hand / cursor dup but the `writeReviveLogoutClearItemsToDB` clear-list was limited to the `player_data` row. Curios slots, Accessories slots (used by The Aether), and Cosmetic Armor Reworked slots are stored in separate tables (`curios.curios_item`, `mod_player_data.data_value` for `mod_id IN ('accessories','cosmeticarmor')`), and the corpse mod's curios/accessories compat catches their items into the corpse on the post-disconnect death finalize — so they were still dup'ing on the next join. r4 extends the revive-logout batch to also clear those columns (set to the empty-map encoding `{}` which the `apply*FromData` functions interpret as "no data, leave slots empty"). NeoForge attachments (`mod_id='neoforge_attachments'`) are deliberately left untouched: they hold per-player progression (Aether `AETHER_PLAYER` — portals, darts, flight timer, life shards; Apotheosis `WORLD_TIER`; Apothic Attributes `AUX_DMG_TRACKER`; Ars Nouveau mana; Iron's Spellbooks mana; etc.), not items. All clears run in the same `executeBatchTransaction` as the core `player_data` UPDATE, so they only commit when the cross-server `last_server` guard passes.

### Correctifs (r4 — French mirror)

- **Dup items revive-disconnect — extension slots mod (r4)** — r3 corrigeait la dup de l'inventaire principal / armure / main secondaire / curseur mais la clear-list de `writeReviveLogoutClearItemsToDB` se limitait à la row `player_data`. Les slots Curios, Accessories (utilisés par The Aether), et Cosmetic Armor Reworked sont stockés dans des tables séparées (`curios.curios_item`, `mod_player_data.data_value` pour `mod_id IN ('accessories','cosmeticarmor')`), et le compat curios/accessories du mod corpse capture leurs items dans le cadavre à la finalisation de mort post-déconnexion — donc ils dupliquaient toujours au prochain join. r4 étend le batch revive-logout pour clear ces colonnes aussi (set à l'encodage map vide `{}` que les fonctions `apply*FromData` interprètent comme "pas de données, laisser slots vides"). Les attachments NeoForge (`mod_id='neoforge_attachments'`) restent volontairement intouchés : ils contiennent de la progression par joueur (Aether `AETHER_PLAYER` — portails, dards, timer de vol, life shards ; Apotheosis `WORLD_TIER` ; Apothic Attributes `AUX_DMG_TRACKER` ; Ars Nouveau mana ; Iron's Spellbooks mana ; etc.), pas des items. Tous les clears tournent dans le même `executeBatchTransaction` que l'UPDATE core `player_data`, donc ils ne commit que quand le guard cross-server `last_server` passe.

---

## [Unreleased] - 2026-05-20 (r3)

### Fixed (English first)

- **Item dup on revive-disconnect — r2 still leaked (r3)** — The r2 fix detected canceled `LivingDeathEvent` via a programmatic listener at LOWEST priority + a heuristic (infinite-duration effects + HP < 50%). It still failed in production because (a) some revive mods may prevent death without ever canceling `LivingDeathEvent` (they cancel `LivingDamageEvent` or use Mixins, so no canceled event ever reaches our listener), and (b) the heuristic required infinite-duration effects which not every revive mod applies. r3 fixes both gaps by moving detection to a `@SubscribeEvent(priority = HIGHEST)` hook (`onPlayerDeathAttempt`) that fires for EVERY `LivingDeathEvent` BEFORE any other handler can cancel it. The cancel filter is bypassed cleanly: at HIGHEST priority, no earlier handler exists, so `isCanceled()` is always `false` when our handler runs and the dispatcher always delivers the event. Adds a new `LivingHealEvent` hook (`onPlayerHeal`) that clears the tracking when the player is healed back to ≥80% maxHealth — covers the legitimate "revived and continued playing" case so a later normal logout isn't wrongly treated as a death-pending disconnect. The heuristic in `onPlayerLogout` is kept as a secondary safety net for revive mods that prevent death entirely without firing `LivingDeathEvent`. Removes the now-unused programmatic listener from `register()` and the dead `if (event.isCanceled())` branch in the LOW-priority `onPlayerDeath`.

### Correctifs (r3 — French mirror)

- **Dup items revive-disconnect — r2 fuyait encore (r3)** — Le fix r2 détectait les `LivingDeathEvent` annulés via un listener programmatique à priorité LOWEST + une heuristique (effets infinite-duration + HP < 50%). Il échouait toujours en production parce que (a) certains mods de revive peuvent empêcher la mort sans jamais annuler `LivingDeathEvent` (ils annulent `LivingDamageEvent` ou utilisent des Mixins, donc aucun event annulé n'arrive à notre listener), et (b) l'heuristique exigeait des effets infinite-duration qui ne sont pas appliqués par tous les mods de revive. r3 corrige les deux failles en déplaçant la détection vers un hook `@SubscribeEvent(priority = HIGHEST)` (`onPlayerDeathAttempt`) qui fire pour TOUT `LivingDeathEvent` AVANT que tout autre handler puisse l'annuler. Le filtre de cancellation est contourné proprement : à priorité HIGHEST, aucun handler antérieur n'existe, donc `isCanceled()` retourne toujours `false` quand notre handler tourne et le dispatcher livre toujours l'event. Ajoute un nouveau hook `LivingHealEvent` (`onPlayerHeal`) qui efface le tracking quand le joueur est soigné à ≥80% de maxHealth — couvre le cas légitime "revived et continue à jouer" pour qu'une déconnexion normale plus tard ne soit pas faussement traitée comme un disconnect death-pending. L'heuristique dans `onPlayerLogout` est conservée comme filet de sécurité secondaire pour les mods de revive qui empêchent complètement la mort sans firer `LivingDeathEvent`. Supprime le listener programmatique désormais inutilisé de `register()` et la branche `if (event.isCanceled())` morte dans le `onPlayerDeath` LOW.

---

## [Unreleased] - 2026-05-20 (r2)

### Fixed (English first)

- **Item dup on revive-disconnect — r1 hardening (r2)** — The r1 fix relied on `@SubscribeEvent(receiveCanceled = true)` to catch canceled `LivingDeathEvent` firings, but **NeoForge bus 8.x ignores this annotation flag at dispatch time** (`SubscribeEventListener.invoke` unconditionally skips canceled events for `ICancellableEvent`; only programmatic `addListener(priority, receiveCanceled, ...)` respects it). So r1 never received the canceled events — the tracking map stayed empty and the bug remained. r2 fixes this with two complementary detection paths: (1) a **programmatic** `LivingDeathEvent` listener registered in `VanillaSync.register()` at LOWEST priority with `receiveCanceled=true`, which actually fires for canceled deaths; (2) a **heuristic** at `onPlayerLogout`: if the player has at least one infinite-duration MobEffect AND health is below 50% of max, treat as downed-state regardless of whether `LivingDeathEvent` was canceled. The heuristic catches revive mods that prevent death via `LivingDamageEvent` cancel or Mixin (no canceled `LivingDeathEvent` ever fires in that case). Diagnostic logging (`[revive-detect]`, `[revive-track]`) now lines the path so future regressions are debuggable from `sync.log`.

## [Unreleased] - 2026-05-20 (r1, superseded by r2)

### Fixed (English first)

- **Item duplication on death + disconnect from revive interface + reconnect** — When a revive mod (Revive Me / Hardcore Revival / Corail Tombstone) canceled `LivingDeathEvent` at NORMAL/HIGH priority, NeoForge skipped PlayerSync's LOW-priority handler entirely (`receiveCanceled` defaulted to `false`), so the mod had no record of the canceled death. The player kept their full inventory in the downed state; on disconnect the logout-save captured that inventory and wrote it to DB. The revive timer then finalized the death post-disconnect, items dropped, and the corpse/gravestone mod created a body holding the same inventory. On reconnect: the player respawned with the restored inventory AND the corpse held a second copy — full duplication. Fix tracks canceled `LivingDeathEvent` firings (via `receiveCanceled = true` on `onPlayerDeath`) in a new `deathCanceledRecently` map (2-min TTL). When a tracked player disconnects with the `keepInventory` game rule off, the new `handleReviveCanceledLogout` path persists progression (xp / effects / score / food / health / advancements) but explicitly clears the item-dropping columns (`inventory`, `armor`, `left_hand`, `cursors`) in DB so the corpse becomes the single source of truth. Tracking entries auto-clear on `PlayerRespawnEvent` and `removePlayerLock`. The keepInventory=on branch falls through to the normal save path (no drop, no corpse, no dup risk — clearing would destroy the player's items).

### Correctifs (r2 — French mirror)

- **Dup items revive-disconnect — r1 endurci (r2)** — Le fix r1 reposait sur `@SubscribeEvent(receiveCanceled = true)` pour attraper les `LivingDeathEvent` annulés, mais **NeoForge bus 8.x ignore ce flag d'annotation au dispatch** (`SubscribeEventListener.invoke` skip toujours les événements annulés pour `ICancellableEvent` ; seul `addListener(priority, receiveCanceled, ...)` programmatique respecte le flag). r1 ne recevait donc jamais les événements annulés — la map de tracking restait vide et le bug persistait. r2 corrige avec deux détections complémentaires : (1) un listener **programmatique** sur `LivingDeathEvent` enregistré dans `VanillaSync.register()` en priorité LOWEST avec `receiveCanceled=true`, qui fire réellement sur les morts annulées ; (2) une **heuristique** dans `onPlayerLogout` : si le joueur a au moins un MobEffect infinite-duration ET HP < 50% du max, traiter comme downed-state quel que soit le statut de cancellation de `LivingDeathEvent`. L'heuristique attrape les mods de revive qui empêchent la mort via cancel de `LivingDamageEvent` ou Mixin (aucun `LivingDeathEvent` annulé ne fire dans ce cas). Logs diagnostiques (`[revive-detect]`, `[revive-track]`) marquent maintenant le chemin pour rendre toute régression future debuggable depuis `sync.log`.

### Correctifs (r1 — superseded by r2)

- **Duplication d'items mort + déconnexion depuis l'interface revive + reconnexion** — Quand un mod de revive (Revive Me / Hardcore Revival / Corail Tombstone) annulait `LivingDeathEvent` en priorité NORMAL/HIGH, NeoForge sautait complètement le handler LOW de PlayerSync (`receiveCanceled` par défaut à `false`), donc le mod n'avait aucune trace de la mort annulée. Le joueur conservait son inventaire complet en état "downed" ; à la déconnexion la sauvegarde logout capturait cet inventaire et l'écrivait en DB. Le timer de revive finalisait ensuite la mort post-déconnexion, les items tombaient, et le mod corpse/gravestone créait un corps contenant le même inventaire. Au reconnect : le joueur respawnait avec l'inventaire restauré ET le corpse contenait une seconde copie — duplication complète. Le fix track les firings de `LivingDeathEvent` annulés (via `receiveCanceled = true` sur `onPlayerDeath`) dans une nouvelle map `deathCanceledRecently` (TTL 2 min). Quand un joueur tracké se déconnecte avec la game rule `keepInventory` désactivée, le nouveau chemin `handleReviveCanceledLogout` persiste la progression (xp / effects / score / food / health / advancements) mais vide explicitement les colonnes d'items-droppables (`inventory`, `armor`, `left_hand`, `cursors`) en DB pour que le corpse devienne la seule source de vérité. Les entrées de tracking sont auto-nettoyées au `PlayerRespawnEvent` et au `removePlayerLock`. La branche keepInventory=on retombe sur le chemin normal de sauvegarde (pas de drop, pas de corpse, pas de risque de dup — vider la DB détruirait les items du joueur).

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
