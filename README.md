# PlayerSync

PlayerSync is a NeoForge / Forge mod for Minecraft 1.21.1 that synchronizes player data across multiple dedicated servers through a shared MySQL database. Inventory, ender chest, XP, advancements, status effects, curios slots, accessories and backpack contents follow players from one server to the next.

## Features

- Atomic, transactional save of every per-player record (single `executeBatchTransaction`, automatic rollback on failure).
- Cross-server anti-duplication: writes are guarded by `last_server`, the previous server's save is awaited on rejoin, and other players' open containers are closed before a snapshot is taken.
- Anti-loss: snapshots that can't read a capability/handler return `null` instead of overwriting the DB with an empty payload. Advancements are guarded by `COALESCE(?, advancements)`.
- HikariCP connection pool tuned for 35+ player servers (15 max / 4 idle / 25 s leak detection).
- Async background writes — the main thread never blocks on MySQL. Dedicated `SyncLogger` daemon flushes `logs/playersync/sync.log` every 500 ms.
- Diagnostic categories: `DUPE_RISK`, `DATA_LOSS`, `RACE`, `GUARD`, `SAVE`, `RESTORE`, `PERF_SLOW` — any silently blocked write is now traced.
- Configurable `table_prefix` so PlayerSync can share a single MySQL database with other mods without table-name collisions.
- Backward-compatible upgrade: existing tables and rows are preserved, removed config keys are silently ignored.

## Compatible Mods

The following mods are explicitly handled by PlayerSync — their per-player state survives a server transfer.

| Mod | Coverage |
|-----|----------|
| [Curios API](https://www.curseforge.com/minecraft/mc-mods/curios) | Functional **and** cosmetic stacks across all slot types |
| [Sophisticated Backpacks](https://www.curseforge.com/minecraft/mc-mods/sophisticated-backpacks) | Equipped & ender-chest backpack contents, upgrades and settings (replace-on-restore fix) |
| [Sophisticated Storage](https://www.curseforge.com/minecraft/mc-mods/sophisticated-storage) | Shulker / barrel / chest contents carried as items (main-thread snapshot) |
| [Sophisticated Core](https://www.curseforge.com/minecraft/mc-mods/sophisticated-core) | Required dependency for the two mods above |
| [Refined Storage](https://www.curseforge.com/minecraft/mc-mods/refined-storage) | Disk contents linked to disk items in the inventory |
| [Accessories](https://github.com/wisp-forest/accessories) | All Accessories slots — used by The Aether |
| [The Aether](https://github.com/The-Aether-Team/The-Aether) | Accessories slots + `AETHER_PLAYER` attachment (portals, dart count, flight timer, life shards, etc.) |
| [Cosmetic Armor Reworked](https://www.curseforge.com/minecraft/mc-mods/cosmetic-armor-reworked) | All 4 cosmetic armor slots |
| [Apotheosis](https://www.curseforge.com/minecraft/mc-mods/apotheosis) | Item DataComponents (affixes, sockets, gems, purity, ...) + `WORLD_TIER` / `RADIAL_MINING_MODE` attachments |
| [Apothic Enchanting](https://www.curseforge.com/minecraft/mc-mods/apothic-enchanting) | DataComponents on items (CRESCENDO, CHROMATIC, ...) |
| [Apothic Attributes](https://www.curseforge.com/minecraft/mc-mods/apothic-attributes) | Bonus attribute modifiers + `AUX_DMG_TRACKER` attachment |
| [Apothic Spawners](https://www.curseforge.com/minecraft/mc-mods/apothic-spawners) | World-side only, no per-player state to sync |
| [Revive Me](https://www.curseforge.com/minecraft/mc-mods/revive-me) | Death event runs at LOW priority + cancel guard → fallen players are not falsely flagged as dead |
| [Corpse](https://www.curseforge.com/minecraft/mc-mods/corpse) / [Gravestone](https://www.curseforge.com/minecraft/mc-mods/gravestone-mod) (+ Curios-Compat) | Compatible — dead players' curios are not overwritten with empty data |

Any other mod that stores per-player state through **NeoForge AttachmentTypes** is automatically synced (Ars Nouveau, Iron's Spellbooks, Pehkui, Spice of Life: Onion, etc.).

## Installation

1. Install NeoForge 1.21.1 (>= 21.1.128) on every server that should share data.
2. Drop `playersync-<version>.jar` into the `mods` folder of each server.
3. Start the server once — `config/playersync-common.toml` is generated.
4. Edit the config:
   - `host`, `db_port`, `user_name`, `password`, `db_name`
   - `Server_id` must be **unique per server**
   - Optional `table_prefix` (e.g. `playersync_`) if you share the database with another mod
5. Restart the server. PlayerSync creates / migrates its tables automatically (existing tables from prior versions are left untouched).

## Credits

Authors: mlus & vyrriox

License: GPL-3.0

---

# PlayerSync (Version Française)

PlayerSync est un mod NeoForge / Forge pour Minecraft 1.21.1 qui synchronise les données joueur entre plusieurs serveurs dédiés via une base MySQL partagée. Inventaire, ender chest, XP, avancements, effets, slots Curios, accessoires et contenu des backpacks suivent le joueur d'un serveur à l'autre.

## Caractéristiques

- Sauvegarde atomique et transactionnelle de chaque enregistrement joueur (un seul `executeBatchTransaction`, rollback automatique en cas d'échec).
- Anti-duplication inter-serveur : écritures gardées par `last_server`, attente du save du serveur précédent au rejoin, fermeture des containers ouverts par d'autres joueurs avant snapshot.
- Anti-perte : si une capacité/handler ne peut pas être lu, le snapshot retourne `null` au lieu d'écraser la BDD avec du vide. Les avancements sont protégés par `COALESCE(?, advancements)`.
- Pool HikariCP réglé pour serveurs 35+ joueurs (15 max / 4 idle / 25 s leak detection).
- Écritures asynchrones — le thread principal ne bloque jamais sur MySQL. Le daemon `SyncLogger` flush `logs/playersync/sync.log` toutes les 500 ms.
- Catégories de log dédiées : `DUPE_RISK`, `DATA_LOSS`, `RACE`, `GUARD`, `SAVE`, `RESTORE`, `PERF_SLOW` — toute écriture silencieusement bloquée est désormais tracée.
- Préfixe de table configurable (`table_prefix`) pour partager une BDD MySQL avec d'autres mods sans collision.
- Mise à niveau rétro-compatible : tables et lignes existantes préservées, clés de config supprimées silencieusement ignorées.

## Mods Compatibles

Les mods suivants sont explicitement supportés par PlayerSync — leurs données joueur survivent à un transfert de serveur.

| Mod | Couverture |
|-----|------------|
| [Curios API](https://www.curseforge.com/minecraft/mc-mods/curios) | Slots fonctionnels **et** cosmétiques |
| [Sophisticated Backpacks](https://www.curseforge.com/minecraft/mc-mods/sophisticated-backpacks) | Contenu, upgrades et settings du backpack équipé et de l'ender chest (correctif replace-on-restore) |
| [Sophisticated Storage](https://www.curseforge.com/minecraft/mc-mods/sophisticated-storage) | Contenu des shulkers / barils / coffres transportés (snapshot main-thread) |
| [Sophisticated Core](https://www.curseforge.com/minecraft/mc-mods/sophisticated-core) | Dépendance des deux mods ci-dessus |
| [Refined Storage](https://www.curseforge.com/minecraft/mc-mods/refined-storage) | Contenu des disques liés aux items disque de l'inventaire |
| [Accessories](https://github.com/wisp-forest/accessories) | Tous les slots Accessories — utilisé par The Aether |
| [The Aether](https://github.com/The-Aether-Team/The-Aether) | Slots Accessories + attachment `AETHER_PLAYER` (portails, fléchettes, timer de vol, life shards, etc.) |
| [Cosmetic Armor Reworked](https://www.curseforge.com/minecraft/mc-mods/cosmetic-armor-reworked) | Les 4 slots d'armure cosmétique |
| [Apotheosis](https://www.curseforge.com/minecraft/mc-mods/apotheosis) | DataComponents items (affixes, sockets, gems, pureté, ...) + attachments `WORLD_TIER` / `RADIAL_MINING_MODE` |
| [Apothic Enchanting](https://www.curseforge.com/minecraft/mc-mods/apothic-enchanting) | DataComponents items (CRESCENDO, CHROMATIC, ...) |
| [Apothic Attributes](https://www.curseforge.com/minecraft/mc-mods/apothic-attributes) | Modifiers d'attributs bonus + attachment `AUX_DMG_TRACKER` |
| [Apothic Spawners](https://www.curseforge.com/minecraft/mc-mods/apothic-spawners) | Côté monde uniquement, aucune donnée joueur à sync |
| [Revive Me](https://www.curseforge.com/minecraft/mc-mods/revive-me) | Event de mort à priorité LOW + garde de cancel → un joueur "fallen" n'est jamais traité comme mort |
| [Corpse](https://www.curseforge.com/minecraft/mc-mods/corpse) / [Gravestone](https://www.curseforge.com/minecraft/mc-mods/gravestone-mod) (+ Curios-Compat) | Compatible — les curios d'un joueur mort ne sont pas écrasés par du vide |

Tout autre mod qui stocke ses données joueur via les **NeoForge AttachmentTypes** est automatiquement synchronisé (Ars Nouveau, Iron's Spellbooks, Pehkui, Spice of Life: Onion, etc.).

## Installation

1. Installer NeoForge 1.21.1 (>= 21.1.128) sur chaque serveur participant au sync.
2. Déposer `playersync-<version>.jar` dans le dossier `mods` de chaque serveur.
3. Lancer le serveur une fois — `config/playersync-common.toml` est généré.
4. Éditer la config :
   - `host`, `db_port`, `user_name`, `password`, `db_name`
   - `Server_id` doit être **unique par serveur**
   - `table_prefix` optionnel (ex : `playersync_`) si la BDD est partagée avec un autre mod
5. Redémarrer le serveur. PlayerSync crée / migre ses tables automatiquement (les tables d'anciennes versions sont conservées intactes).

## Credits

Auteurs : mlus & vyrriox

Licence : GPL-3.0
