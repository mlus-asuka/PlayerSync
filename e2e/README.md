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
./e2e/run-e2e.sh         # ~2 min on warm caches, longer on first run (builds the server image)
```

Runs in CI as the `e2e` job of `.github/workflows/build.yml` on pull requests and pushes
to `1.**`; it runs after the `build` job and reuses that job's jar artifact instead of
rebuilding. Node (>= 18, the baseline the bot's mineflayer toolchain needs) must be on
`PATH`. Dependencies install from the committed `bot/package-lock.json` (`npm ci`). Keep
the environment up after a failure for debugging with `KEEP=1 ./e2e/run-e2e.sh`; tear down
manually with `docker compose -f e2e/docker-compose.yml down -v`.

## Scenarios

### `bot/test-sync-across-servers.js` — happy path

1. Bot joins server A (new player → PlayerSync inserts a row in `player_data`).
2. Via RCON the bot receives 7 diamonds and 100 XP; the bot confirms them client-side.
3. Bot disconnects — PlayerSync persists state asynchronously on logout.
4. Bot joins server B and must end up with the same inventory and XP (±2 for the
   float rounding inherent in the level/progress XP encoding).

### `bot/test-already-online.js` — already-online kick

Positive test for `kick_when_already_online`: a bot online on server A, then a second
login on server B that must be refused while the player is online on A and accepted once
the bot has left. The reject-then-accept contrast proves the online state is what gates the
join; the refusal must also be attributable to the gate — the `playersync.already_online`
kick, or the reason-less login close the mod's racy refusal path can produce — so a
refusal with any other stated reason fails the test.

## Layout

- `docker-compose.yml` — db + toxiproxy + `server-a`/`server-b`
  (`itzg/minecraft-server`); the mod jar path is injected via `PLAYERSYNC_JAR` by the
  runner script. The servers reach MariaDB through toxiproxy (`host = "toxiproxy"`);
  its HTTP API is on `127.0.0.1:8474` for fault injection.
- `Dockerfile` — builds `playersync-e2e-forge:1.20.1-47.4.0` on top of the pinned
  `itzg/minecraft-server` base, baking the Forge install into a layer. Both servers run
  that one image, so Forge is downloaded once at image build instead of by each server on
  first boot.
- `config/server-{a,b}/playersync-common.toml` — identical DB settings, distinct
  `Server_id` (1 and 2). The id **must** be pinned: the mod defaults to a random id
  per load when the key is missing.
- `bot/` — the mineflayer test client. It also connects to MariaDB directly (published on
  `127.0.0.1:13306`, **not** via toxiproxy) to assert persisted `player_data` state —
  reading ground truth unperturbed by injected faults.
