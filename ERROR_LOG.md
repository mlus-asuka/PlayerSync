# PlayerSync — Error Log

Journal des erreurs rencontrées et corrigées. Chaque entrée documente un bug, sa cause racine, son correctif et la règle de prévention à appliquer systématiquement.

---

## [2026-05-20 21:15] — r4 fix caused false positive: inventory disappears 10 min after death + reco

**Context** : User signale qu'un joueur, après être mort puis revived, et avoir joué 10 minutes, a perdu son inventaire à une déco/reco normale. Le dup principal (r4) est bien corrigé mais introduction d'un false-positive sur les déconnexions légitimes post-revive.

**Error** : `handleReviveCanceledLogout` se déclenche pour un joueur qui n'est PAS en état revive-pending au moment du logout, clearant son inventaire DB → à la reconnexion l'inventaire est vide.

**Root cause** : Deux chemins de false-positive dans r4 :

1. **L'heuristique `infiniteEffects + HP < 50%`** était trop large. Beaucoup de mods modernes appliquent des effets infinite-duration sur le joueur en jeu normal :
   - The Aether : effets racial / vol persistants
   - Apotheosis : affixes qui grantent des effets permanents
   - Iron's Spellbooks : marqueurs de spell appris / mana auras
   - Ars Nouveau : auras de mana
   Combiné à un HP < 50% (combat normal, faim, fall damage léger), l'heuristique fire à tort.

2. **TTL trop court (2 min)**. Si `onPlayerHeal` ne fire pas (revive mod utilise `setHealth()` direct au lieu de `heal()`), le tracking reste actif. Mais après 2 min, le TTL le rend inerte → `trackedCancel` retourne false au logout. Hmm wait, ça ne cause pas le bug r4 ici... mais peut-être que le joueur est resté en revive interface pendant 10 min, et le TTL expirait, MAIS l'heuristique firait quand même → fix path déclenché → DB cleared → à la reconnexion inventaire vide.

**Fix** :
- **Suppression complète de l'heuristique**. La détection se fait UNIQUEMENT via le tracking de `LivingDeathEvent` à priorité HIGHEST (qui capture déjà tous les événements de mort).
- **Check HP au logout durci** à `HP ≤ 1.0 absolu OU isDeadOrDying()`. Plus de seuil ratio. Les mods de revive clamp typiquement le joueur downed à exactement 1 HP (demi-cœur) — ce check le détecte. Un joueur revived avec HP > 1 (même 1.5 ou 2) n'est plus considéré downed.
- **Seuil `onPlayerHeal` baissé** à `HP > 1.0` (au lieu de `≥80% maxHealth`). N'importe quel heal qui amène le HP au-dessus de 1 HP clear le tracking. Couvre les mods de revive qui partiel-heal à 5 HP, 10 HP, etc.
- **Clear explicite dans la boucle auto-save** : toutes les 5 minutes, pour chaque joueur eligible (alive + synced + HP > 1.0), on clear `deathCanceledRecently`. Couvre le cas où `LivingHealEvent` ne fire pas (revive mod utilise `setHealth()` direct).
- **TTL étendu à 1 heure** (au lieu de 2 min) en filet de sécurité pour les rares cas où aucun clear explicite ne fire (joueur reste en revive interface > 5 min sans heal event).

Multi-layered defense : (a) HIGHEST hook capture tous les événements de mort, (b) onPlayerHeal clear sur heal, (c) onPlayerRespawn clear sur respawn, (d) auto-save clear sur eligibility + HP haut, (e) removePlayerLock clear sur fin de session, (f) TTL 1h en backup, (g) check HP strict au logout.

**Prevention** :
- **Ne JAMAIS faire confiance à une heuristique "comportementale" en code de sync de données**. Les signaux comme `infiniteEffects` ou `low HP` ont trop de mods qui peuvent les produire en gameplay normal. N'utiliser que des signaux EXPLICITES (events spécifiques) pour déclencher une action destructive comme clearer une row DB.
- **Pour une détection d'état transitoire avec TTL, multi-coucher les chemins de cleanup**. Au minimum : event-based (heal, respawn), state-based (alive + healthy), session-based (logout/lock-remove), et TTL-based. Si une seule couche échoue (revive mod utilise setHealth → pas de heal event), les autres compensent.
- **Pour les seuils HP**, préférer les valeurs ABSOLUES aux ratios. Les ratios de maxHealth donnent des seuils trop variables (4 HP / 20 max ≠ 4 HP / 40 max si maxHealth changée par mod). Un seuil absolu (HP > 1.0) est plus prévisible et matche les conventions de mods de revive (clamp à exactement 1 HP).

---

## [2026-05-20 20:30] — r3 fixed main inv but mod slots (curios / accessories / cosmetic armor) still dup

**Context** : User a testé r3 (commit `b34cd3a`). Inventaire principal / armure / main secondaire / curseur ne dupliquent plus. Mais les slots Curios, slots Accessories (utilisés par The Aether), et Cosmetic Armor Reworked dupliquent encore.

**Error** : Duplication partielle — seulement sur les slots de mods, pas les slots vanilla.

**Root cause** : `writeReviveLogoutClearItemsToDB` ne mettait à jour QUE la row `player_data`. Les items de mods sont dans des tables séparées :
- Curios → table `curios` (colonne `curios_item`), keyed par player UUID
- Accessories → table `mod_player_data` avec `mod_id='accessories'`
- Cosmetic Armor → table `mod_player_data` avec `mod_id='cosmeticarmor'`

Au moment où le corpse mod capture les items dropped post-finalize, il capture aussi les curios/accessories/cosmétiques via leur compat respectif. La DB gardait l'ancienne copie de ces items → au rejoin, restored + corpse contient = dup.

**Fix** : Extension de `writeReviveLogoutClearItemsToDB` pour clear aussi :
- `UPDATE curios SET curios_item='{}' WHERE uuid=?`
- `UPDATE mod_player_data SET data_value='{}' WHERE uuid=? AND mod_id='accessories'`
- `UPDATE mod_player_data SET data_value='{}' WHERE uuid=? AND mod_id='cosmeticarmor'`

Les fonctions `applyCuriosFromData` / `applyAccessoriesFromData` / `applyCosmeticArmorFromData` détectent toutes `data == null || data.length() <= 2` et skip la restauration (les slots restent vides après le clear initial). `{}` (length 2) déclenche ce skip-path.

Tous les clears sont dans le même `executeBatchTransaction` que l'UPDATE core, donc atomiquement guardés par `last_server` pour la safety cross-server.

**MUST PRESERVE** :
- `mod_player_data` avec `mod_id='neoforge_attachments'` — contient la progression par joueur (Aether AETHER_PLAYER : portails / dards / timer de vol / life shards ; Apotheosis WORLD_TIER ; Apothic Attributes AUX_DMG_TRACKER ; Ars Nouveau mana ; Iron's Spellbooks mana ; etc.). Ce ne sont PAS des items et ils ne droppent PAS sur la mort. Clear → destruction de progression.
- `backpack_data`, `sophisticatedstorage_data`, RS2 data — keyed par ITEM UUID, pas par player UUID. Le backpack/shulker drop dans le corpse avec son UUID propre, et la data suit l'item au retrieval. Pas de dup.
- `enderchest` — ne drop pas en vanilla, ne forme pas de cadavre.

**Prevention** :
- **Pour tout fix de duplication d'items, TOUJOURS auditer TOUTES les tables qui stockent des items**, pas juste celle qu'on suspecte. PlayerSync utilise au moins 5 tables différentes pour des items (player_data, curios, mod_player_data, backpack_data, et SS/RS2 sont dans modPlayerData ou backpack_data selon le mod).
- **Distinguer items-keyed-par-player vs items-keyed-par-UUID-d'item** : seuls les premiers ont besoin d'être cleared (les seconds suivent leur item physique).
- **Distinguer items vs progression** dans `mod_player_data` par `mod_id` : clear items, préserver progression.

---

## [2026-05-20 19:45] — r2 fix still leaks: revive mods may prevent death without canceling LivingDeathEvent

**Context** : User a testé le fix r2 (commit `8e945a8`) → bug toujours présent. Duplication reproductible. Le tracking via le listener programmatique LOWEST + receiveCanceled=true ne fire pas, et l'heuristique (infinite effects + HP < 50%) ne fire pas non plus.

**Error** : Ni le tracking ni l'heuristique ne détectaient l'état "revive me interface".

**Root cause** : Deux failles dans r2 :
1. **Le listener programmatique LOWEST avec receiveCanceled=true ne fire que si LivingDeathEvent est ANNULÉ**. Mais certains mods de revive empêchent la mort SANS annuler `LivingDeathEvent` — ils annulent `LivingDamageEvent` plus tôt dans le pipeline, OU utilisent un Mixin sur `LivingEntity.die()` ou `LivingEntity.actuallyHurt()`. Dans ces cas, `LivingDeathEvent` ne fire JAMAIS, donc notre listener n'a rien à voir, et le tracking reste vide.
2. **L'heuristique (infinite-duration effects + HP < 50%) est trop restrictive**. Le mod de revive utilisé par le user n'applique peut-être pas d'effet infinite-duration, OU clamp HP à une valeur ≥ 50% du max, OU les deux. L'heuristique ne fire pas → fall-through au save normal → duplication.

**Fix** :
- **Hook à priorité HIGHEST** : nouveau `@SubscribeEvent(priority = HIGHEST) onPlayerDeathAttempt(LivingDeathEvent)`. À priorité HIGHEST, AUCUN autre handler n'a encore eu la chance d'annuler l'event. `event.isCanceled()` est toujours `false` au moment où on tourne. Le dispatcher délivre TOUJOURS l'event à notre handler. → On capture TOUTE tentative de mort, qu'elle soit ensuite annulée ou non.
- **Suppression du listener programmatique LOWEST** (devenu redondant).
- **Suppression de la branche `if (event.isCanceled())` morte dans `onPlayerDeath` LOW** (le dispatcher la skip déjà — code mort).
- **Nouveau hook `LivingHealEvent`** : si le joueur est soigné à ≥80% de maxHealth, clear le tracking. Couvre le cas "revived avec succès et continue à jouer" pour éviter de clear l'inventaire DB lors d'une déconnexion normale plus tard.
- **Conservation de l'heuristique en filet de sécurité** pour les mods qui empêchent la mort sans firer `LivingDeathEvent` du tout (cancel `LivingDamageEvent` / Mixin pur).

**Prevention** :
- **Pour détecter une cancellation d'event, hooker à HIGHEST priority** (avant tout cancel) plutôt qu'à LOWEST + `receiveCanceled=true`. Plus simple, plus robuste, marche pour tous les types de mods (priority-based, Mixin-based, alternative-event-based).
- **Ne jamais reposer sur un seul signal pour un fix critique de duplication**. Avoir au moins (a) un event-based primary + (b) un state-based fallback (heuristique sur health/effects/etc.).
- **Le check de `event.isCanceled()` dans un handler `@SubscribeEvent` est presque toujours du code mort** dans NeoForge bus 8.x. Le dispatcher skip les events annulés automatiquement. Soit on n'a pas besoin du check (le handler ne fire jamais sur cancel), soit on doit utiliser `addListener(priority, true, ...)` programmatique pour recevoir les cancels.

---

## [2026-05-20 18:30] — r1 fix non-effective: @SubscribeEvent(receiveCanceled=true) ignored by NeoForge bus 8.x

**Context** : User a testé le fix r1 (commit `39aee07`) → bug toujours présent, duplication identique. Le tracking via `deathCanceledRecently` ne fonctionnait pas.

**Error** : `@SubscribeEvent(priority = LOW, receiveCanceled = true)` ne livrait JAMAIS les événements annulés au handler annoté.

**Root cause** : Lecture du source de `net.neoforged:bus:8.0.1` (jar dans `~/.gradle/caches`) :
- `SubscribeEventListener.invoke()` (line 47-49) :
  ```java
  if (!((ICancellableEvent) event).isCanceled()) {
      handler.invoke(event);
  }
  ```
  Le dispatcher skip TOUJOURS les événements annulés pour les `ICancellableEvent`, **sans consulter le flag `receiveCanceled` de l'annotation**.
- Le constructeur `SubscribeEventListener(target, method)` lit `subInfo = method.getAnnotation(SubscribeEvent.class)` mais n'utilise que `subInfo.priority()` — jamais `subInfo.receiveCanceled()`.
- `ListenerList.canUnwrapListeners = !ICancellableEvent.class.isAssignableFrom(eventClass)` → pour les événements cancellables, on ne peut PAS unwrap le check de cancellation.
- Seul `EventBus.addListener(priority, receiveCanceled, eventType, consumer)` programmatique respecte le flag via `passNotGenericFilter(receiveCanceled)`.

**Fix** :
- Le handler annoté `@SubscribeEvent(priority = LOW)` reste pour les morts non-annulées (real death path).
- Ajout de `VanillaSync.register()` qui enregistre programmatiquement un listener sur `LivingDeathEvent` avec `NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, LivingDeathEvent.class, VanillaSync::onCanceledLivingDeath)`. Ce listener REÇOIT les événements annulés et alimente `deathCanceledRecently`.
- Ajout d'une **heuristique fallback** dans `onPlayerLogout` : si le joueur a au moins un MobEffect `isInfiniteDuration() == true` ET HP < 50% du max → traiter comme downed-state. Couvre les mods de revive qui empêchent la mort via cancel de `LivingDamageEvent` ou Mixin (cas où aucun `LivingDeathEvent` n'est annulé du tout).
- Logging diagnostique `[revive-track]` au moment du cancel et `[revive-detect]` au logout (montre quel signal a déclenché : `trackedCancel=true/false`, `heuristic=true/false`, HP ratio, keepInventory).

**Prevention** :
- **NeoForge bus 8.x : `@SubscribeEvent(receiveCanceled = true)` est SILENCIEUSEMENT NON-FONCTIONNEL pour les ICancellableEvent**. Toujours utiliser `EVENT_BUS.addListener(priority, true, eventType, consumer)` programmatique pour les listeners qui doivent recevoir des événements annulés. Documenter dans le code chaque fois qu'un listener doit être programmatique pour cette raison.
- **Toujours vérifier l'API d'un event bus en allant lire le source du dispatcher** (`SubscribeEventListener`, `EventBus.addListener`, `ListenerList.unwrapListeners`) plutôt que de se fier aux comments existants — ceux du code initial PlayerSync disaient "`priority=LOW + skip canceled events defends against mods like Revive Me`" mais cette logique était fausse car le handler ne fire jamais en premier lieu.
- **Pour les fixes critiques de duplication, toujours implémenter au moins deux détections indépendantes** (event-based + heuristic). Une seule détection qui échoue silencieusement = bug persistant en production.

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
