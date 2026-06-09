# Changelog

All notable changes to **PlayerSync** are documented here.

---

## [Unreleased] - 2026-06-09 (full audit: server performance + security + dup hardening)

### Performance (English first)

- **Executor pool was pinned at 4 threads** — Standard `ThreadPoolExecutor` semantics only spawn threads beyond `corePoolSize` when the work queue is FULL; the 4..16 config therefore never created thread #5 until 512 tasks were backlogged. Every save path (logout, auto-save, death, shutdown) funneled through 4 workers. Now `core == max == 16` with `allowCoreThreadTimeOut(true)` — real 16-wide parallelism, idle threads still reaped after 30s.
- **Main thread can no longer execute blocking DB work under load** — `CallerRunsPolicy` ran rejected tasks inline on the submitting thread; for tasks submitted from the server thread (joins, tick saves) that meant full DB transactions — or doPlayerJoin's up-to-60s poll — freezing ticks under queue overflow. New rejection policy: background submitters keep classic CallerRuns backpressure, main-thread submissions divert to a dedicated single-thread overflow lane. No save is ever dropped.
- **`SELECT *` removed from the join path** — the join read pulled the `advancements` MEDIUMBLOB (hundreds of KB to MB on modpacks) on every join and discarded it (`onDataPackSyncEvent` fetches it separately). Explicit 10-column projection now.
- **Advancements SELECT off the main thread** — `onDatapackSyncEvent` ran a synchronous MySQL round-trip on the main thread per join. The SELECT now runs on the executor; only the file write + `reload()` hop back to the main thread.
- **New-player init no longer blocks the main thread** — the first-join path ran a synchronous INSERT plus one REPLACE per backpack/SS/RS2 item inside `server.execute()` (worst case 10s `connectionTimeout` stall per statement). Converted to the established snapshot-on-main / write-on-executor pattern; the legacy `store()` method is removed. The `player_synced` tag is added only after the INSERT lands.
- **Backpack write amplification eliminated** — the snapshot hash-skip did not cover backpack/SS MEDIUMBLOBs: every effective auto-save rewrote every blob even when unchanged (the dominant write volume), and conversely a change INSIDE a backpack was skipped when the core hash matched. New per-storage-UUID hash skips unchanged blobs on auto-save paths AND persists backpack-only changes; logout/shutdown/emergency keep always-write semantics.
- **Save batch reduced from 9 to 5 statements** — curios / mod_player_data writes used UPDATE + `INSERT IGNORE...SELECT` pairs (each blob bound twice). Replaced by single guarded `INSERT ... ON DUPLICATE KEY UPDATE` upserts that preserve the `last_server` guard. `REPLACE INTO backpack_data` (InnoDB delete+insert) became an upsert too.
- **`rewriteBatchedStatements` actually engages now** — `executeBatchTransaction` groups consecutive identical statements into JDBC `addBatch()`/`executeBatch()` so Connector/J performs true multi-row rewrites (N backpack rows = 1 round-trip). Unique-SQL entries (the guarded core UPDATE whose `counts[0]` callers check) keep exact per-statement counts.
- **3 mod-data SELECTs per join collapsed into 1** — accessories / cosmeticarmor / neoforge_attachments were read with three sequential round-trips on the same uuid; a single `WHERE uuid=?` range scan on the (uuid, mod_id) PK now returns all rows.
- **Unbounded advancements cache fixed** — the PHASE 17 file cache retained the FULL advancements JSON for every player that ever joined (hundreds of MB over weeks on a hub). Entries are now evicted on logout via `removePlayerLock`.
- **ReviveMe reflection cached** — `isReviveMeFallen` resolved `Class.forName` + 2× `getMethod` on every call (every logout, every join, plus re-invocations inside log statements). Handles are now resolved once and cached; call sites pass the booleans down instead of re-invoking.
- **HikariCP config keys actually applied** — `hikari_pool_max_size` / `hikari_leak_threshold_ms` were documented and displayed by `/playersync status` but `initPool()` hardcoded 15/25000. They are now read (with safe fallbacks); `PoolStatsReporter` thresholds derive from the same config instead of hardcoded 14/15 and 400/512.
- **Index added for cleanup queries** — `player_data` had only the uuid PK; crash-recovery / clearorphans / peerkill queries full-scanned the table (with gap locks). Idempotent startup migration adds `idx_online_server (online, last_server)`.
- **Admin commands moved off the main thread** — Brigadier executes handlers on the server thread; status/info/dump/wipe/orphans/clearorphans/peers/peerkill/cleanup/inventory each ran 1-3 synchronous JDBC calls there (up to 10s stall each on a slow DB). All DB-touching command bodies now run on the executor and deliver chat output back via `server.execute()`. Item deserialization for `inventory` stays on the main thread (registry access).
- **Staggered auto-save skips disconnected players** — players who logged out while queued were still snapshotted (entity retains the `player_synced` tag) and burned a DB round-trip before the online=0 guard caught it; `hasDisconnected()` check added.

### Fixed (English first)

- **MySQL credential disclosure to a rogue/MITM server** — `allowPublicKeyRetrieval=true` was hardcoded while `use_ssl` defaults to false: with `caching_sha2_password` over a non-TLS link, Connector/J RSA-encrypts the password with WHATEVER public key the server presents — a DNS-spoofed or MITM'd endpoint could recover the DB password. The flag is now appended only when `use_ssl=true` or the host is loopback (the default localhost config keeps working unchanged); a clear WARN explains the remediation for remote no-TLS setups.
- **Log injection + log-flood via item NBT** — item NBT contains player-authored content (anvil renames, book pages) and SNBT does not escape raw newlines, so logging payloads verbatim let a crafted book forge multi-line entries in `latest.log` (the file carrying `[admin-dump]`/`[admin-wipe]` audit records); one WARN also dumped the full compound tag for every placeholder item on every join. All payload logging now goes through a 256-char, control-character-neutralizing preview helper; the per-placeholder dump is removed.
- **Zip-bomb OOM via DB blobs** — `deserializeBinaryBase64Tag` used `NbtAccounter.unlimitedHeap()`: a crafted/corrupt row (gzip ratios up to ~1000×) could allocate multi-GB tags. Decompression is now capped at 64 MB (far beyond any legitimate payload; writes are already capped at 10 MB serialized).
- **Latent SQL-injection surface removed** — the `String.format`-based `executeQuery`/`executeUpdate(String, Object...)` helpers (zero data-bearing callers, but one future misuse away from injection) are deleted; DDL goes through a single-arg `executeUpdate(String)`, data through the `?`-placeholder variants.
- **sync.log rotation now actually runs** — rotation executed only at startup, so a long session grew the file without bound; the flush thread re-checks every ~10s. The documented `log_rotation_size_mb` / `log_rotation_max_files` config keys are finally read instead of hardcoded constants.

### Changed (English first)

- **Destructive admin commands now require permission level 3** — `/playersync wipe|resync|clearorphans|peerkill|cleanup` were gated at level 2, the level command blocks and datapack functions execute at — any player able to place a command block could wipe any player's cross-server data. Level 3 excludes command blocks/datapacks while keeping console, RCON and full-op access. Read-only commands stay at level 2.
- **Dead code removed** — unused `peerHeartbeatAgeMs`, the legacy `store()` method, and the format-string SQL helpers.

### Performance (miroir français)

- **Le pool d'exécution était figé à 4 threads** — La sémantique standard de `ThreadPoolExecutor` ne crée des threads au-delà de `corePoolSize` que lorsque la queue est PLEINE ; la config 4..16 ne créait donc jamais le thread n°5 avant 512 tâches en attente. Tous les chemins de sauvegarde passaient par 4 workers. Désormais `core == max == 16` avec `allowCoreThreadTimeOut(true)` — vrai parallélisme à 16, les threads inactifs sont toujours libérés après 30s.
- **Le main thread ne peut plus exécuter de travail DB bloquant sous charge** — `CallerRunsPolicy` exécutait les tâches rejetées sur le thread soumetteur ; pour les tâches du thread serveur cela signifiait des transactions DB complètes — ou le poll de 60s de doPlayerJoin — gelant les ticks. Nouvelle politique : les soumetteurs background gardent la contre-pression CallerRuns classique, les soumissions du main thread sont déviées vers une voie de débordement dédiée. Aucune sauvegarde n'est jamais perdue.
- **`SELECT *` supprimé du chemin de join** — la lecture tirait le MEDIUMBLOB `advancements` (centaines de Ko à plusieurs Mo) à chaque join pour le jeter (`onDataPackSyncEvent` le récupère séparément). Projection explicite de 10 colonnes.
- **SELECT advancements hors du main thread** — `onDatapackSyncEvent` faisait un aller-retour MySQL synchrone sur le main thread par join. Le SELECT tourne sur l'executor ; seuls l'écriture fichier + `reload()` reviennent au main thread.
- **L'init nouveau-joueur ne bloque plus le main thread** — le premier join exécutait un INSERT synchrone plus un REPLACE par backpack/SS/RS2 dans `server.execute()` (pire cas 10s de stall par statement). Converti au pattern snapshot-main / write-executor ; la méthode legacy `store()` est supprimée. Le tag `player_synced` n'est ajouté qu'après l'INSERT.
- **Amplification d'écriture backpack éliminée** — le hash-skip ne couvrait pas les MEDIUMBLOBs backpack/SS : chaque auto-save effective réécrivait tous les blobs même inchangés, et inversement un changement DANS un backpack était ignoré si le hash core était identique. Nouveau hash par UUID de stockage : skip des blobs inchangés en auto-save ET persistance des changements backpack-only ; logout/shutdown/emergency gardent l'écriture inconditionnelle.
- **Batch de sauvegarde réduit de 9 à 5 statements** — les écritures curios / mod_player_data utilisaient des paires UPDATE + `INSERT IGNORE...SELECT` (chaque blob lié deux fois). Remplacées par des upserts `INSERT ... ON DUPLICATE KEY UPDATE` gardés qui préservent le guard `last_server`. `REPLACE INTO backpack_data` (delete+insert InnoDB) devient un upsert aussi.
- **`rewriteBatchedStatements` s'active enfin** — `executeBatchTransaction` groupe les statements identiques consécutifs via `addBatch()`/`executeBatch()` pour de vrais multi-row rewrites (N rows backpack = 1 aller-retour). Les entrées à SQL unique (l'UPDATE core gardé dont les appelants vérifient `counts[0]`) gardent des compteurs exacts.
- **3 SELECT mod-data par join fusionnés en 1** — accessories / cosmeticarmor / neoforge_attachments étaient lus en trois allers-retours séquentiels ; un seul range scan `WHERE uuid=?` sur la PK (uuid, mod_id) retourne tout.
- **Cache advancements non borné corrigé** — le cache fichier PHASE 17 retenait le JSON complet de chaque joueur jamais connecté (centaines de Mo sur un hub). Les entrées sont évincées au logout via `removePlayerLock`.
- **Réflexion ReviveMe cachée** — `isReviveMeFallen` résolvait `Class.forName` + 2× `getMethod` à chaque appel (chaque logout, chaque join, plus les ré-invocations dans les logs). Handles résolus une fois et cachés ; les sites d'appel passent les booléens au lieu de ré-invoquer.
- **Les clés de config HikariCP sont enfin appliquées** — `hikari_pool_max_size` / `hikari_leak_threshold_ms` étaient documentées et affichées par `/playersync status` mais `initPool()` codait 15/25000 en dur. Elles sont maintenant lues ; les seuils de `PoolStatsReporter` dérivent de la même config.
- **Index ajouté pour les requêtes de cleanup** — `player_data` n'avait que la PK uuid ; crash-recovery / clearorphans / peerkill scannaient toute la table (avec gap locks). Migration idempotente au démarrage : `idx_online_server (online, last_server)`.
- **Commandes admin sorties du main thread** — Brigadier exécute les handlers sur le thread serveur ; status/info/dump/wipe/orphans/clearorphans/peers/peerkill/cleanup/inventory y faisaient 1-3 appels JDBC synchrones (jusqu'à 10s de stall chacun). Tous les corps de commande DB tournent sur l'executor et renvoient la sortie chat via `server.execute()`. La désérialisation d'items pour `inventory` reste sur le main thread (accès registres).
- **L'auto-save échelonnée saute les joueurs déconnectés** — les joueurs partis pendant qu'ils étaient en file étaient quand même snapshotés et brûlaient un aller-retour DB avant que le guard online=0 ne les attrape ; check `hasDisconnected()` ajouté.

### Correctifs (miroir français)

- **Divulgation du mot de passe MySQL à un serveur pirate/MITM** — `allowPublicKeyRetrieval=true` était codé en dur alors que `use_ssl` est false par défaut : avec `caching_sha2_password` sans TLS, Connector/J chiffre le mot de passe avec N'IMPORTE QUELLE clé publique présentée par le serveur — un endpoint MITM pouvait récupérer le mot de passe DB. Le flag n'est ajouté que si `use_ssl=true` ou si l'hôte est loopback (la config localhost par défaut fonctionne sans changement) ; un WARN clair explique la remédiation pour les setups distants sans TLS.
- **Injection de logs + flood via NBT d'items** — le NBT contient du contenu écrit par les joueurs (renommages d'enclume, pages de livres) et le SNBT n'échappe pas les retours à la ligne : un livre forgé pouvait injecter de fausses entrées multi-lignes dans `latest.log` (le fichier qui porte les traces d'audit `[admin-dump]`/`[admin-wipe]`) ; un WARN dumpait aussi le tag complet pour chaque item placeholder à chaque join. Tout le logging de payload passe par un helper de prévisualisation (256 chars, caractères de contrôle neutralisés) ; le dump par placeholder est supprimé.
- **OOM par zip-bomb via blobs DB** — `deserializeBinaryBase64Tag` utilisait `NbtAccounter.unlimitedHeap()` : une row forgée/corrompue (ratios gzip jusqu'à ~1000×) pouvait allouer des tags de plusieurs Go. Décompression plafonnée à 64 Mo.
- **Surface d'injection SQL latente supprimée** — les helpers `executeQuery`/`executeUpdate(String, Object...)` à base de `String.format` (zéro appelant avec données, mais à un mésusage de l'injection) sont supprimés ; le DDL passe par `executeUpdate(String)` mono-argument, les données par les variantes à placeholders `?`.
- **La rotation de sync.log tourne vraiment** — la rotation ne s'exécutait qu'au démarrage, donc une longue session faisait grossir le fichier sans limite ; le thread de flush revérifie toutes les ~10s. Les clés documentées `log_rotation_size_mb` / `log_rotation_max_files` sont enfin lues.

### Modifications (miroir français)

- **Les commandes admin destructives exigent le niveau de permission 3** — `/playersync wipe|resync|clearorphans|peerkill|cleanup` étaient au niveau 2, celui des command blocks et des fonctions datapack — tout joueur capable de poser un command block pouvait wiper les données cross-serveur de n'importe qui. Le niveau 3 exclut command blocks/datapacks tout en gardant console, RCON et full-op. Les commandes en lecture seule restent au niveau 2.
- **Code mort supprimé** — `peerHeartbeatAgeMs` inutilisé, la méthode legacy `store()`, et les helpers SQL à format-string.

---

## [Unreleased] - 2026-05-21 (r7)

### Fixed (English first)

- **Revive-disconnect dup — the `dieOnDisconnect` path (r7)** — r6 fixed the case where a fallen player reconnects STILL fallen, but the dup came back for the more common config. Decompiling `revive_me-1.21.1-5.7.14` (`CapabilityEvents.onLogout`) + `corpse-neoforge-1.21.1-1.1.13` (`DeathEvents`) revealed the real mechanism: Revive Me's `onLogout` handler, when the config `dieOnDisconnect` is true, calls `FallenData.forceDeath()` **on disconnect** — applying lethal damage that fires `LivingDeathEvent` → `LivingDropsEvent`, which the Corpse mod turns into a corpse holding the full inventory (plus curios / cosmetics via `corpsecurioscompat` / `cosmeticcorpsecompat`). Because Revive Me's `onLogout` and PlayerSync's `onPlayerLogout` are both at NORMAL priority, their order is undefined — and whenever PlayerSync ran first it captured and saved the still-attached pre-death inventory, which the next join restored alongside the corpse = duplication. r6's join-side skip never triggered because the reconnecting player is no longer "fallen" (they were force-killed). r7 adds the matching logout-side guard: `onPlayerLogout` now detects `isReviveMeFallen(player) || player.isDeadOrDying()` (exact, via Revive Me's `FallenData`) and routes to the new `handleFallenLogout` — which clears every item-bearing DB column (inventory / armor / left_hand / cursors / curios / accessories / cosmetic_armor) so the corpse is the single source of truth, regardless of handler order. Gated on the `keepInventory` game rule being OFF (with it ON, death drops nothing and forms no corpse — clearing would destroy the player's items, so the normal save runs instead). r6's join-side skip is kept for the `dieOnDisconnect=false` path where the player reconnects still fallen.

### Correctifs (r7 — French mirror)

- **Dup revive-déconnexion — le chemin `dieOnDisconnect` (r7)** — r6 corrigeait le cas où un joueur fallen se reconnecte ENCORE fallen, mais la dup revenait pour la config la plus courante. La décompilation de `revive_me-1.21.1-5.7.14` (`CapabilityEvents.onLogout`) + `corpse-neoforge-1.21.1-1.1.13` (`DeathEvents`) a révélé le vrai mécanisme : le handler `onLogout` de Revive Me, quand la config `dieOnDisconnect` est activée, appelle `FallenData.forceDeath()` **à la déconnexion** — applique des dégâts létaux qui firent `LivingDeathEvent` → `LivingDropsEvent`, que le mod Corpse transforme en cadavre contenant l'inventaire complet (+ curios / cosmétiques via `corpsecurioscompat` / `cosmeticcorpsecompat`). Comme `onLogout` de Revive Me et `onPlayerLogout` de PlayerSync sont tous deux en priorité NORMAL, leur ordre est indéfini — et chaque fois que PlayerSync passait en premier, il capturait et sauvegardait l'inventaire pré-mort encore attaché, que le prochain join restaurait à côté du cadavre = duplication. Le skip côté-join de r6 ne se déclenchait jamais car le joueur qui se reconnecte n'est plus "fallen" (il a été force-tué). r7 ajoute la garde côté-logout correspondante : `onPlayerLogout` détecte maintenant `isReviveMeFallen(player) || player.isDeadOrDying()` (exact, via `FallenData` de Revive Me) et route vers le nouveau `handleFallenLogout` — qui vide toutes les colonnes DB porteuses d'items (inventory / armor / left_hand / cursors / curios / accessories / cosmetic_armor) pour que le cadavre soit la source unique de vérité, quel que soit l'ordre des handlers. Conditionné à la game rule `keepInventory` désactivée (avec elle activée, la mort ne drop rien et ne forme pas de cadavre — vider détruirait les items du joueur, donc la sauvegarde normale tourne à la place). Le skip côté-join de r6 est conservé pour le chemin `dieOnDisconnect=false` où le joueur se reconnecte encore fallen.

---

## [Unreleased] - 2026-05-20 (r6)

### Fixed (English first)

- **Revive-disconnect dup — definitive fix via exact ReviveMe state detection (r6)** — r2–r5 tried to detect the "downed" state with logout-side heuristics (canceled-`LivingDeathEvent` tracking, HP thresholds, infinite-effect signatures). Every variant either missed the state (dup returned) or false-positived (inventory wrongly cleared). Root cause finally identified by decompiling `revive_me-1.21.1-5.7.14.jar`: Revive Me holds a downed player in a "fallen" state exposed by `invoker54.reviveme.common.capability.FallenData` (a NeoForge AttachmentType `revive_me:fallen_data`), it **pauses the fall timer on logout** and resumes it on reconnect. The dup was a race in `doPlayerJoin`: it restored the DB inventory AFTER the resumed timer finalized the death and a corpse/gravestone mod had already captured the items. r6 replaces all heuristics with an **exact** check — new `isReviveMeFallen(player)` reflectively calls `FallenData.get(player).isFallen()` (zero false positives: the player is fallen iff Revive Me says so). `doPlayerJoin` now **skips the entire DB data apply** when the rejoining player is still fallen (or has already died during the join delay — `isDeadOrDying()` guard covers the race), leaving the vanilla `.dat` inventory in place as the single source of truth. A successful revive is then captured by the next auto-save / logout-save; a finalized death is captured by `onPlayerRespawn` (empty inventory → DB). No dup, no item loss, no heuristic. All r2–r5 logout-side machinery removed (`deathCanceledRecently` map, `onPlayerDeathAttempt`/`onPlayerHeal` hooks, `handleReviveCanceledLogout`, `writeReviveLogoutClearItemsToDB`, auto-save tracking-clear) — the logout save is now unchanged from the pre-r2 baseline.

### Correctifs (r6 — French mirror)

- **Dup revive-déconnexion — correctif définitif via détection exacte de l'état ReviveMe (r6)** — r2–r5 tentaient de détecter l'état "downed" avec des heuristiques côté-logout (tracking de `LivingDeathEvent` annulé, seuils HP, signatures d'effets infinis). Chaque variante soit ratait l'état (dup revenait), soit false-positivait (inventaire vidé à tort). Cause racine enfin identifiée en décompilant `revive_me-1.21.1-5.7.14.jar` : Revive Me maintient un joueur downed dans un état "fallen" exposé par `invoker54.reviveme.common.capability.FallenData` (un AttachmentType NeoForge `revive_me:fallen_data`), il **met le timer de chute en pause au logout** et le reprend au reconnect. La dup était une race dans `doPlayerJoin` : il restaurait l'inventaire DB APRÈS que le timer repris finalise la mort et qu'un mod corpse/gravestone ait déjà capturé les items. r6 remplace toutes les heuristiques par un check **exact** — nouveau `isReviveMeFallen(player)` appelle réflexivement `FallenData.get(player).isFallen()` (zéro false positive : le joueur est fallen si et seulement si Revive Me le dit). `doPlayerJoin` **skip maintenant tout l'apply des données DB** quand le joueur qui rejoint est encore fallen (ou est déjà mort pendant le délai de join — le guard `isDeadOrDying()` couvre la race), laissant l'inventaire `.dat` vanilla en place comme source unique de vérité. Un revive réussi est ensuite capturé par la prochaine auto-save / logout-save ; une mort finalisée est capturée par `onPlayerRespawn` (inventaire vide → DB). Pas de dup, pas de perte d'items, pas d'heuristique. Toute la machinerie logout-side r2–r5 est supprimée (`deathCanceledRecently`, hooks `onPlayerDeathAttempt`/`onPlayerHeal`, `handleReviveCanceledLogout`, `writeReviveLogoutClearItemsToDB`, clear de tracking dans l'auto-save) — la sauvegarde logout est de nouveau identique à la baseline pré-r2.

---

## [Unreleased] - 2026-05-20 (r5)

### Fixed (English first)

- **Inventory disappears after a revive + deco/reco — r5 hardens detection against false positives** — A player reported losing their inventory on disconnect/reconnect 10 minutes after dying. The r4 detection had two false-positive paths: (a) the heuristic (infinite-duration effect + HP < 50%) fired for any player wearing a long-lived effect from Aether / Apotheosis / Iron's Spellbooks / Ars Nouveau / etc. who happened to disconnect with sub-50% HP (after combat or simply not yet regenerated post-revive). (b) The 2-minute TTL on the canceled-death tracking expired before the player either healed enough to clear it (`onPlayerHeal` required ≥80% maxHealth) or hit the auto-save loop. r5 removes the heuristic entirely, tightens the logout-time check to `HP ≤ 1.0 OR isDeadOrDying()` (matches the typical revive-mod downed-state clamp at exactly 1 HP), drops the `onPlayerHeal` clear threshold to `HP > 1.0` so any non-trivial heal ends the tracking, adds an explicit tracking-clear in the auto-save loop (every 5 minutes, players with HP > 1.0 get the entry dropped), and extends the TTL to 1 hour as a safety net for the rare path where none of the explicit clears fire. Combined, the detection now triggers ONLY when the player is genuinely still in a downed-state disconnect at the moment of logout — never on a legitimate post-revive disconnect.

### Correctifs (r5 — French mirror)

- **Inventaire qui disparait après un revive + deco/reco — r5 durcit la détection contre les false positives** — Un joueur a rapporté avoir perdu son inventaire à la deco/reco 10 minutes après être mort. La détection r4 avait deux chemins de false-positive : (a) l'heuristique (effet infinite-duration + HP < 50%) firait pour tout joueur portant un effet long-lived d'Aether / Apotheosis / Iron's Spellbooks / Ars Nouveau / etc. qui se déconnectait avec moins de 50% HP (après combat ou simplement pas encore régénéré post-revive). (b) Le TTL de 2 minutes sur le tracking de mort-annulée expirait avant que le joueur soit soigné assez pour clear le tracking (`onPlayerHeal` exigeait ≥80% maxHealth) ou que la boucle d'auto-save fasse son cycle. r5 supprime entièrement l'heuristique, durcit le check au logout à `HP ≤ 1.0 OU isDeadOrDying()` (correspond au clamp typique des mods de revive à exactement 1 HP en état downed), baisse le seuil de clear `onPlayerHeal` à `HP > 1.0` pour que tout heal non-trivial termine le tracking, ajoute un clear explicite du tracking dans la boucle auto-save (toutes les 5 minutes, les joueurs avec HP > 1.0 voient leur entrée droppée), et étend le TTL à 1 heure en filet de sécurité pour le rare chemin où aucun clear explicite ne fire. Combiné, la détection ne se déclenche QUE quand le joueur est vraiment encore en état downed-disconnect au moment du logout — jamais sur une déconnexion légitime post-revive.

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
