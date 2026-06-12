# End-to-end test

Boots a shared MariaDB and **two** Forge 1.20.1 servers with the built PlayerSync jar
(docker), then drives a vanilla-protocol bot (mineflayer) through the core promise of
the mod: state acquired on server A is restored when joining server B.

This is possible with a vanilla client because PlayerSync is a server-side-only mod
(`displayTest="IGNORE_SERVER_VERSION"`, no network channels) — bots can join a Forge
server without Forge. Servers run in offline mode, so the bot's UUID is derived from
its username and identical on both servers.

## Run

```sh
./gradlew e2e            # build the jar and run the whole suite (Docker + Node required)
```

Or drive the two steps yourself:

```sh
./gradlew build          # produce build/libs/playersync-*.jar
./e2e/run-e2e.sh         # ~2 min on warm caches, longer on first run (Forge download)
```

Node (>= 18, for the global `fetch` the bot uses) must be on `PATH`. Dependencies
install from the committed `bot/package-lock.json` (`npm ci`). Keep the environment
up after a failure for debugging with `KEEP=1 ./e2e/run-e2e.sh`; tear down manually
with `docker compose -f e2e/docker-compose.yml down -v`.

## What the test asserts (`bot/test.js`)

1. Bot joins server A (new player → PlayerSync inserts a row in `player_data`).
2. Via RCON the bot receives 7 diamonds and 100 XP; the bot confirms them client-side.
3. Bot disconnects — PlayerSync persists state asynchronously on logout.
4. Bot joins server B and must end up with the same inventory and XP (±2 for the
   float rounding inherent in the level/progress XP encoding).

## Layout

- `docker-compose.yml` — db + `server-a`/`server-b` (`itzg/minecraft-server`); the mod
  jar path is injected via `PLAYERSYNC_JAR` by the runner script.
- `config/server-{a,b}/playersync-common.toml` — identical DB settings, distinct
  `Server_id` (1 and 2). The id **must** be pinned: the mod defaults to a random id
  per load when the key is missing.
- `bot/` — the mineflayer test client.
