'use strict';

/*
 * Reproduces the ghost-online race: a player who disconnects mid-sync must not be left
 * marked online.
 *
 * doPlayerJoin writes online=1 early in the async sync task. If the player disconnects
 * mid-task the logout handler writes online=0, but the sync task's online=1 can land
 * afterwards — leaving the player "online" on a server they left and locked out of every
 * other sync server by the already-online check until this server's heartbeat goes stale.
 *
 * The window is normally sub-second. A toxiproxy latency toxic (~2s per DB round-trip)
 * widens it so the bot can disconnect while the sync task is still in flight. (Latency,
 * not a paused DB: some queries run on the server thread, so a frozen DB deadlocks it.)
 * The sync task must re-check hasDisconnected() and revert its own online=1 write. Without
 * that revert the bot stays ghost-online and joining the other server fails with
 * playersync.already_online.
 *
 * Exit code 0 = pass, 1 = fail.
 */

const {
  SERVER_A, SERVER_B, sleep, countItem, waitFor,
  offlineUUID, connectDb, queryPlayer, waitForPlayer, createHarness,
} = require('./lib');

const { log, join, rcon, startWatchdog } = createHarness('e2e-race');

const BOT_NAME = 'RaceTester';
const BOT_UUID = offlineUUID(BOT_NAME);
const TOXIPROXY_URL = process.env.TOXIPROXY_URL || 'http://127.0.0.1:8474';

const SEED_DIAMONDS = 3;
// ~2s per DB round-trip. The sync task's online=1 write is several round-trips in, so this
// keeps it from landing before we disconnect, and keeps the online=1 -> 0 revert slow
// enough to observe on our (direct, un-toxic'd) DB connection.
const DB_LATENCY_MS = 2000;
// Disconnect this long after spawn: well before the sync task's online=1 write, so the
// disconnect reliably lands inside the window.
const DISCONNECT_AFTER_MS = 2000;
// The sync task makes many latency-delayed DB round-trips (advancements included), so its
// online=1 write lands tens of seconds after the disconnect — poll generously for it.
const SYNC_OBSERVE_MS = 90_000;

async function removeDbLatency() {
  const res = await fetch(`${TOXIPROXY_URL}/proxies/mariadb/toxics/db_latency`, { method: 'DELETE' });
  if (!res.ok && res.status !== 404) {
    throw new Error(`Failed to remove latency toxic: HTTP ${res.status} ${await res.text()}`);
  }
}

async function setDbLatency(latencyMs) {
  if (latencyMs > 0) {
    // Clear any toxic left behind by a previous (KEEP=1) run so the POST can't 409.
    await removeDbLatency();
    const res = await fetch(`${TOXIPROXY_URL}/proxies/mariadb/toxics`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name: 'db_latency',
        type: 'latency',
        stream: 'downstream',
        attributes: { latency: latencyMs, jitter: 0 },
      }),
    });
    if (!res.ok) {
      throw new Error(`Failed to add latency toxic: HTTP ${res.status} ${await res.text()}`);
    }
    log(`DB latency toxic enabled: ${latencyMs}ms per round-trip`);
  } else {
    await removeDbLatency();
    log('DB latency toxic removed');
  }
}

async function main() {
  const db = await connectDb();
  try {
    // --- Phase 0: seed a known-good database row for the bot ---
    const seedBot = await join(SERVER_A, BOT_NAME);
    await waitForPlayer(db, BOT_UUID, (p) => p && p.online === 1, 30_000,
      'seed: player_data row created on server A');
    await rcon(SERVER_A, `give ${BOT_NAME} minecraft:diamond ${SEED_DIAMONDS}`);
    await waitFor('seed diamonds on server A', 30_000, () => countItem(seedBot, 'diamond') === SEED_DIAMONDS);
    seedBot.quit();
    await waitForPlayer(db, BOT_UUID, (p) => p && p.online === 0, 30_000,
      'seed: player offline after logout');
    log(`Seed complete: ${SEED_DIAMONDS} diamonds persisted, player offline`);

    // --- Phase 1: disconnect while the sync task is mid-flight, then watch the race ---
    await setDbLatency(DB_LATENCY_MS);
    try {
      const raceBot = await join(SERVER_A, BOT_NAME);
      await sleep(DISCONNECT_AFTER_MS);

      // Non-vacuous guard: prove the disconnect really lands before the sync task's
      // online=1 write. If online is already 1 the window closed too fast and the test
      // would otherwise pass without exercising the race — fail loudly instead.
      const midFlight = await queryPlayer(db, BOT_UUID);
      if (!midFlight || midFlight.online !== 0) {
        throw new Error(
          `Race window not exercised: expected online=0 (sync still mid-flight) at disconnect, got ` +
          `${JSON.stringify(midFlight)}. Raise DB_LATENCY_MS or lower DISCONNECT_AFTER_MS.`);
      }
      log('Disconnecting while the sync task is still mid-flight (online not yet set)');
      raceBot.quit();

      // The sync task now writes online=1 *after* the logout's online=0 — the ghost. Under
      // latency the sync runs for tens of seconds, so the online=1 -> 0 transition is wide
      // enough to observe on our direct DB connection. Seeing online=1 proves the ghost was
      // really created this run, so the revert assertion below is not vacuous. The revert
      // back to 0 is the behaviour under test. Without it online stays 1, this times out,
      // and server B would kick with playersync.already_online until server A's heartbeat
      // goes stale.
      await waitForPlayer(db, BOT_UUID, (p) => p && p.online === 1, SYNC_OBSERVE_MS,
        'sync task wrote online=1 after disconnect (ghost-online window reproduced)');
      await waitForPlayer(db, BOT_UUID, (p) => p && p.online === 0, SYNC_OBSERVE_MS,
        'online reverted to 0 after disconnect-during-sync (no ghost)');
    } finally {
      await setDbLatency(0);
    }

    // --- Phase 2: end-to-end confirmation that server B accepts the join and restores state.
    const verifyBot = await join(SERVER_B, BOT_NAME);
    await waitFor('seed diamonds restored on server B', 30_000,
      () => countItem(verifyBot, 'diamond') === SEED_DIAMONDS);
    log(`Server B accepted the join and restored ${countItem(verifyBot, 'diamond')} diamonds`);
    verifyBot.quit();
    // Confirm the logout persisted (online=0) rather than sleeping a guessed duration.
    await waitForPlayer(db, BOT_UUID, (p) => p && p.online === 0, 30_000,
      'player offline after leaving server B');
  } finally {
    await db.end();
  }

  log('PASS: no ghost-online state after disconnect during sync');
  process.exit(0);
}

startWatchdog(5 * 60_000);

main().catch(async (err) => {
  // Best effort: never leave the latency toxic behind a failure.
  try { await setDbLatency(0); } catch (ignored) { /* already gone */ }
  console.error(`[e2e-race] FAIL: ${err.stack || err}`);
  process.exit(1);
});
