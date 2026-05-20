# PlayerSync — Error Log

Journal des erreurs rencontrées et corrigées. Chaque entrée documente un bug, sa cause racine, son correctif et la règle de prévention à appliquer systématiquement.

---

## [2026-05-20 14:00] — Item duplication on death + disconnect from revive interface + reconnect

**Context** : Un joueur meurt, le mod Revive Me (ou Hardcore Revival / Corail Tombstone) affiche son interface "downed/revive" en annulant `LivingDeathEvent`. Le joueur se déconnecte depuis cette interface. À la reconnexion : il respawn avec son inventaire complet ET un cadavre/gravestone au point de mort contient également l'inventaire complet — duplication intégrale.

**Error** : Duplication reproductible 100% avec ReviveMe + un mod corpse/gravestone.

**Root cause** :
1. `@SubscribeEvent` de NeoForge a `receiveCanceled = false` par défaut → un handler n'est PAS appelé pour un événement annulé sauf opt-in explicite.
2. `onPlayerDeath` était `@SubscribeEvent(priority = LOW)` (sans `receiveCanceled = true`) → quand ReviveMe annule `LivingDeathEvent` en NORMAL/HIGH, le handler PlayerSync est SAUTÉ. Le check `if (event.isCanceled()) return;` était donc dead code.
3. PlayerSync n'avait aucune trace de l'état "downed" du joueur. À la déconnexion, `onPlayerLogout` exécutait son chemin normal de save : snapshot capturait l'inventaire COMPLET (encore sur le joueur, car ReviveMe n'avait pas drop les items), écriture en DB.
4. Post-déconnexion : le timer revive expirait, la mort se finalisait, les items tombaient au sol, le mod corpse/gravestone créait un corps avec l'inventaire complet.
5. Reconnexion : `doPlayerJoin` restaurait l'inventaire depuis DB (complet) + le cadavre dans le monde contenait l'inventaire complet → 2× items.

**Fix** :
- `onPlayerDeath` annoté `@SubscribeEvent(priority = LOW, receiveCanceled = true)` → le handler reçoit maintenant les événements annulés.
- Branche `if (event.isCanceled())` ajoute le joueur à `deathCanceledRecently` (ConcurrentHashMap uuid → timestamp, TTL 2 min).
- `onPlayerLogout` consulte `deathCanceledRecently` AVANT le chemin normal de save. Si entrée récente :
  - Si `keepInventory=ON` : fall-through au chemin normal (pas de drop, pas de corpse, pas de dup risque).
  - Si `keepInventory=OFF` : appelle `handleReviveCanceledLogout` qui persiste la progression (xp / effects / score / food / health / advancements) MAIS écrit explicitement des valeurs vides (`{}` / `B64:e30=`) dans `inventory` / `armor` / `left_hand` / `cursors`. `online=0` et `logout_started_at=NULL` set atomiquement dans le même UPDATE.
- Nouvelle méthode `writeReviveLogoutClearItemsToDB` bypasse le guard `refuse_empty_inventory_write` (l'écriture vide est INTENTIONNELLE ici).
- Tracking auto-nettoyé : `PlayerRespawnEvent` (joueur ressuscité) + `removePlayerLock` (nettoyage de session) + TTL 2 min.
- `lastWrittenSnapshotHash.remove(uuid)` dans la BG task pour qu'une auto-save pending ne puisse pas ressusciter l'inventaire effacé via le skip de hash.

**Prevention** :
- **TOUJOURS spécifier `receiveCanceled = true` sur un handler qui doit fonctionner après cancellation**. Le check `if (event.isCanceled())` ne suffit pas si NeoForge n'appelle même pas le handler.
- **NE JAMAIS faire confiance à un comment qui dit "we run after cancel and check isCanceled"** sans vérifier les flags d'annotation. La sémantique NeoForge des handlers d'événements annulés est opt-in.
- **Tout chemin de save qui s'exécute pendant un état transitoire (downed, mort-pas-encore-finalisée, respawn-pas-encore-validé) DOIT vérifier la game rule `keepInventory`** avant de décider quoi écrire en DB — un blanket-clear casse le cas où les items doivent rester sur le joueur.

---

## [2026-04-22 02:54] — Item duplication on drop + quick disconnect + reconnect

**Context** : Un joueur drop un item au sol, se déconnecte très rapidement, puis se reconnecte → l'item est présent deux fois (en inventory restauré + encore au sol).

**Error** : Duplication systématique reproductible en production.

**Root cause** : Race condition entre `onPlayerSaveToFile` background task (auto-save périodique) et `onPlayerLogout` background task.
1. `SaveToFile` capture un snapshot sur main thread AVANT le drop (item encore en inventory) → task async soumise.
2. Le joueur drop l'item → inventory vide, ItemEntity dans le monde.
3. Le joueur disconnect → logout capture un snapshot FRESH (sans item), soumet le write.
4. Les deux BG tasks s'exécutent en parallèle. Si la task auto-save (qui portait une snapshot STALE avec l'item) commit APRÈS la task logout (qui portait FRESH sans l'item), la DB finit en STALE.
5. Reconnexion → inventory restauré avec l'item + ItemEntity toujours au sol → 2 copies.

**Fix** (commit `bea5f80`) : Triple guard dans l'auto-save BG task :
- Early skip si `pendingLogoutSaves.containsKey(uuid)` avant tryLock.
- Re-check sous lock après tryLock (race window fermée).
- `SELECT online FROM player_data WHERE uuid=?` — skip si online=0 (logout a committé).

Logout BG task acquiert maintenant `bgLock.lock()` (blocking) pour sérialiser proprement avec les auto-save BG qui utilisent `tryLock`. `removePlayerLock` réordonné avant `bgLock.unlock()` pour que les auto-save BG qui wake après unlock voient `containsKey=false` et skip.

**Prevention** : **JAMAIS de BG task qui modifie la DB sans un guard `online=0` + `pendingLogoutSaves` check**. Si deux paths peuvent écrire le même row, ils DOIVENT partager un lock blocking OU le path "fresh" doit être détectable via DB state (online flag, version column).

---

## [2026-04-22 03:15] — Backpack duplication on cross-server transfer

**Context** : Un joueur utilise un backpack Sophisticated Backpacks sur Server A, change de serveur, et constate que le contenu du backpack est dupliqué.

**Error** : Duplication systématique d'items dans les backpacks et shulkers Sophisticated Storage lors de transferts cross-server ou reconnexions.

**Root cause** : `BackpackStorage.setBackpackContents()` et `ItemContentsStorage.setStorageContents()` en amont sont des **merges shallow**, pas des replaces. Quand le restore applique le snapshot sauvegardé, il MERGE avec les contents existants en mémoire (SavedData persistée sur disk localement ou vue ouverte par un autre joueur). Les sous-tags "items" survivent → duplication.

**Fix** (commit `c84f920`) :
- Backpack : appel `store.removeBackpackContents(uuid)` EXPLICITE avant `setBackpackContents`. Si l'API throw (absent dans certaines versions), fallback reflection qui parcourt les champs `Map` de `BackpackStorage` et remove l'entrée directement.
- SS : nouveau helper `clearSSStorageContents` qui tente `removeStorageContents(UUID)` via reflection, puis fallback reflection sur champs Map. `setDirty()` appelé pour forcer le flush.
- Les deux paths passent maintenant une **copie défensive** (`nbt.copy()`) à l'upstream setter, jamais la référence partagée.

**Prevention** :
- **Toujours clear avant restore pour toute structure qui merge au lieu de replace** (backpack, SS, RS2 disks).
- **Toujours passer une copie défensive** d'un CompoundTag à un setter qui peut la stocker en interne.
- **Logger `clear_via=api/reflection`** pour diagnostiquer les régressions upstream.

---

## [2026-04-22 03:20] — Cross-server saves can overwrite claimed data

**Context** : Deux serveurs sauvent un même joueur simultanément (edge case lors de changements de serveurs rapides).

**Error** : Les données de l'un écrasent silencieusement les données de l'autre. Backpack/SS/RS2 perdus.

**Root cause** : `writeSnapshotToDB` retournait `void`. Même si son guard `last_server=?` bloquait le write du core player_data (rows affected = 0), les appels downstream `saveBackpackSnapshots` / `saveSSSnapshots` / `saveRS2DisksByLevel` s'exécutaient INCONDITIONNELLEMENT et écrasaient `backpack_data` (qui n'a pas de guard propre — keyé par storage UUID, pas player UUID).

**Fix** (commit `c84f920`) : `writeSnapshotToDB` retourne maintenant `boolean`. Les 5 callers (logout, shutdown, auto-save SaveToFile, staggered auto-save, death-save) vérifient le retour et **short-circuitent** les writes downstream si le core a été blocké.

**Prevention** : **Une fonction qui a un guard silencieux DOIT signaler son résultat au caller**. Ne jamais supposer que les writes downstream sont implicitement protégés par un guard en amont — vérifier explicitement.

---

## [2026-04-22 03:25] — 30s delay on player join (RACE timeout 60/60)

**Context** : À chaque connexion, log flood `Waiting for server X to finish saving (attempt 60/60)` et le joueur attend 30s avant de récupérer ses données.

**Error** : Poll timeout systématique sur des server_ids qui n'existent plus ou sur un server_id=0.

**Root cause** :
- Le poll `doPlayerJoin` attend que l'autre serveur clear `online=0`. Si l'autre serveur a crashé sans le faire (pas de shutdown hook), le poll attend jusqu'à épuisement des 60 tentatives.
- `server_id=0` est une ligne orpheline héritée d'une écriture legacy (avant que le default `Random().nextInt(1, MAX-1)` soit appliqué).

**Fix** (commit `c84f920`) :
- Nouvelle méthode `isPeerServerStale(peerId, staleMs)` qui check `server_info.last_update`. Si l'heartbeat est vieux de >60s OU si `peerId == 0`, le poll considère le serveur comme zombie et force-clear `online=0`.
- Poll max passé de 60 à 120 tentatives (60s total) pour couvrir les shutdowns lents.
- Phase 3 : `HeartbeatService` tick toutes les 10s → permet aux peers de détecter les zombies.
- Phase 3 : `CrashRecovery.clearOrphanedOnlineFlags()` au boot → nettoie les rows stuck à online=1 après un crash ungracieux.

**Prevention** : **Tout état "en cours" en DB doit avoir un heartbeat OU un timeout**. Un flag `online=1` sans heartbeat est un bug en attendant de se produire (le process qui l'a set peut crasher).

---

## [2026-04-22 03:30] — StoreCurios NPE / data wipe on dead player

**Context** : Un joueur meurt puis se déconnecte rapidement. Son curios sont vidés de la DB.

**Error** : Méthode legacy `StoreCurios` écrivait un flatMap vide quand `CuriosApi.getCuriosInventory(player)` retournait un `Optional.empty()` (capability détachée après death).

**Root cause** : La méthode utilisait `handlerOpt.ifPresent(...)` mais fallait au `REPLACE INTO` même si le flatMap était vide → wipe DB data pour un joueur mort.

**Fix** (commit `c84f920`) : Early return avec log `WARN [store-curios] handler unavailable for UUID — skipping write to avoid wiping DB data` si `handlerOpt.isEmpty()`.

**Prevention** : **Ne JAMAIS écrire un état "vide" dans la DB si la source est incertaine**. Une capability absente ≠ joueur sans curios — c'est un état indéterminé. Skip write + log.

---

## [2026-04-22 03:40] — Player data loss on kill -9 / OOM

**Context** : Process serveur tué via `kill -9` ou OOM — au redémarrage, les joueurs qui étaient online ne récupèrent pas leurs données des dernières minutes.

**Error** : `ServerStoppingEvent` n'est pas déclenché lors d'un kill ungracieux, donc aucune save n'est exécutée. Les rows `player_data` restent aussi à `online=1` → le poll de doPlayerJoin sur un autre serveur attend 30s pour rien.

**Fix** (commit `746cb56`, Phase 3) :
- `CrashRecovery.installShutdownHook(() -> emergencyFlushAll())` — JVM hook non-daemon enregistré au boot. Appelle une méthode synchrone qui snapshot et write tous les joueurs online sans passer par l'executor (qui peut être déjà mort).
- Marque `server_info.enable=0` à la sortie pour notifier les peers.
- `CrashRecovery.clearOrphanedOnlineFlags()` au boot suivant — clear les rows stuck et log le nombre via SyncLogger.
- `HeartbeatService` tick toutes les 10s pendant le run — permet aux peers de détecter la mort.

**Prevention** :
- **Tout process long-running doit avoir un JVM shutdown hook** pour couvrir SIGTERM / kill doux / OOM soft.
- **Tout flag "en cours" persistant doit avoir un recovery path au boot suivant**.
- **Un heartbeat périodique est obligatoire** si d'autres processus dépendent de savoir si on est alive.

---

## [2026-04-22 03:50] — Inventory loss window of 30 min between auto-saves

**Context** : Les auto-saves ne se déclenchaient que lors des PlayerEvent.SaveToFile natifs (cadence vanilla = autosave world, typiquement 6000 ticks). Si un crash survenait entre deux saves, jusqu'à 15+ minutes de jeu étaient perdus.

**Fix** (commit `c70ca9f`, Phase 4) :
- `PeriodicSaveService` — scheduler indépendant qui déclenche un full-flush toutes les `auto_save_interval_minutes` (défaut 10). Hops au main thread pour snapshotter, puis soumet les writes async via `snapshotAndQueueSave`.
- `onPlayerChangeDimension` — trigger additionnel gated par `save_on_dimension_change` (défaut false). Sauve avant teleport cross-dimension.

**Prevention** : **Ne jamais dépendre uniquement des events du framework** pour déclencher une sauvegarde critique. Doubler avec un scheduler indépendant et rendre l'intervalle configurable.

---

## [2026-04-22 04:00] — Executor queue saturation invisible

**Context** : Sous charge (35+ joueurs), l'executor `PlayerSync` peut saturer (queue >400) et déclencher `CallerRunsPolicy` qui bloque le main thread. Aucune alerte dans les logs.

**Fix** (commit `bd0482c`, Phase 5) :
- `PoolStatsReporter` — scheduler dédié 5-min qui log `[POOL] executor active/queue/idle, hikari active/idle`.
- WARN log si queue > 400/512 ou hikari active >= 14/15.
- Accesseur `JDBCsetUp.getPoolMXBean()` pour exposer Hikari en read-only.

**Prevention** : **Tout pool/queue critique doit être monitoré périodiquement** avec des seuils d'alerte sous la capacité max. Invisible ≠ sain.

---
