# PlayerSync

PlayerSync is a Minecraft Forge mod that synchronizes player data across multiple servers using a MySQL backend. It allows players to maintain their inventory, equipment, experience, advancements, and more when moving between servers in a network.

## Mod Support

The following mods are explicitly handled by PlayerSync — their per-player state survives a server transfer:

* [Curios API](https://www.curseforge.com/minecraft/mc-mods/curios) — functional **and** cosmetic stacks across all slot types
* [Sophisticated Backpacks](https://www.curseforge.com/minecraft/mc-mods/sophisticated-backpacks) — equipped & ender-chest backpack contents, upgrades and settings
* [Sophisticated Storage](https://www.curseforge.com/minecraft/mc-mods/sophisticated-storage) — shulker / barrel / chest contents carried as items
* [Sophisticated Core](https://www.curseforge.com/minecraft/mc-mods/sophisticated-core) — required dependency for the two above
* [Refined Storage](https://www.curseforge.com/minecraft/mc-mods/refined-storage) — disk contents linked to disk items in the inventory
* [Accessories](https://github.com/wisp-forest/accessories) — all Accessories slots (used by The Aether)
* [The Aether](https://github.com/The-Aether-Team/The-Aether) — Accessories slots + `AETHER_PLAYER` attachment (portals, dart count, flight timer, life shards…)
* [Cosmetic Armor Reworked](https://www.curseforge.com/minecraft/mc-mods/cosmetic-armor-reworked) — the 4 cosmetic armor slots
* [Apotheosis](https://www.curseforge.com/minecraft/mc-mods/apotheosis) — item DataComponents (affixes, sockets, gems, purity…) + `WORLD_TIER` / `RADIAL_MINING_MODE` attachments
* [Apothic Enchanting](https://www.curseforge.com/minecraft/mc-mods/apothic-enchanting) — DataComponents on items (CRESCENDO, CHROMATIC…)
* [Apothic Attributes](https://www.curseforge.com/minecraft/mc-mods/apothic-attributes) — bonus attribute modifiers + `AUX_DMG_TRACKER` attachment
* [Apothic Spawners](https://www.curseforge.com/minecraft/mc-mods/apothic-spawners) — world-side only, no per-player state to sync
* [Revive Me](https://www.curseforge.com/minecraft/mc-mods/revive-me) — death event runs at LOW priority + cancel guard, fallen players are not falsely flagged as dead
* [Corpse](https://www.curseforge.com/minecraft/mc-mods/corpse) / [Gravestone](https://www.curseforge.com/minecraft/mc-mods/gravestone-mod) (+ Curios-Compat) — dead players' curios are not overwritten with empty data

Any other mod that stores its per-player state through **NeoForge AttachmentTypes** is synced automatically (Ars Nouveau, Iron's Spellbooks, Pehkui, Spice of Life: Onion, etc.).

Any other mods support is also possible.

## Development Setup

### Database Setup (Docker)

A `docker-compose.yml` file is provided for easily setting up a MariaDB database instance for development testing.

1.  Make sure Docker is installed.
1.  Inside your work directory run:
    ```sh
    docker compose up -d
    ```
    This will download the MariaDB image (if not already present) and start a database container in the background.
1.  Stoppinng the Database
    ```sh
    docker compose down
    ```

**Data Persistence:** The database uses a Docker volume, ensuring your data persists even if you stop and restart the containers.

#### Database Management Tool
The `docker-compose.yml` also includes an [Adminer](https://www.adminer.org/) service, a lightweight database management tool.

* Access Adminer in your web browser at http://localhost:8080.
* Log in using the server with
  - username: `playersync`
  - database: `playersync`
  - password: see [docker-compose.yml](./docker-compose.yml)

For debugging purposes, you can enable `use_legacy_serialization` to have readable database fields. This can cause crashes and unintended side-effects. **Do not enable this on a production server if not absolutely necessary!**


### Running the Mod

The project uses Gradle for building and running. Use the provided Gradle wrapper (`gradlew` for Linux/macOS, `gradlew.bat` for Windows).

1.  Make sure that the MySQL database you configured is running.
1.  Run the Server
    ```sh
    ./gradlew runServer
    ```
    or on Windows:
    ```bat
    .\gradlew.bat runServer
    ```
    This task compiles the mod and starts a dedicated Minecraft server instance with the mod loaded in the `run` directory.
1.  Run the Client
    ```sh
    ./gradlew runClient
    ```
    or on Windows:
    ```bat
    .\gradlew.bat runClient
    ```
