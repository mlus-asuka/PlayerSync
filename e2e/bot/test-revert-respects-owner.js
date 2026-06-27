'use strict';

/*
 * The disconnect-during-sync revert must not clobber a session the player has since opened
 * on another server.
 *
 * When a player disconnects mid-sync, the sync task calls markOffline() to undo its own
 * online=1 write. That UPDATE must be scoped to "online=0 only if this server still owns the
 * row" (last_server = our id). Otherwise a late revert from server A overwrites online=1 for
 * a player who has already reconnected to server B. The player then looks offline, the
 * already-online guard no longer protects them, and two concurrent sessions can write the
 * same row (item duplication).
 *
 * We reproduce the race deterministically with the same toxiproxy latency trick the
 * ghost-online test uses: disconnect while the sync task is in flight so its revert fires
 * tens of round-trips later, and in that window inject last_server = server B's id to
 * simulate the player having moved to B. With the last_server guard A's revert is a no-op and
 * online stays 1. Without the guard online is clobbered to 0.
 *
 * Exit code 0 = pass, 1 = fail.
 */

const {
  SERVER_A, sleep,
  offlineUUID, connectDb, queryPlayer, waitForPlayer, createHarness,
} = require('./lib');

const { log, join, startWatchdog } = createHarness('e2e-owner');

const BOT_NAME = 'OwnerGuardTester';
const BOT_UUID = offlineUUID(BOT_NAME);
const SERVER_B_ID = 2; // server-b's Server_id (see e2e/config/server-b/playersync-common.toml)
const TOXIPROXY_URL = process.env.TOXIPROXY_URL || 'http://127.0.0.1:8474';

const DB_LATENCY_MS = 2000;
const DISCONNECT_AFTER_MS = 2000;
// Generous upper bound to observe the sync task's online=1 write under latency.
const SYNC_OBSERVE_MS = 90_000;
// After injecting last_server=B, watch this long for A's revert to clobber online to 0. The
// revert is only a few latency round-trips away (~tens of seconds worst case). If nothing
// clobbers within this window the guard held. The window is generous so that a slow run
// cannot pass merely because the revert had not fired yet.
const CLOBBER_WATCH_MS = 90_000;

async function removeDbLatency() {
  const res = await fetch(`${TOXIPROXY_URL}/proxies/mariadb/toxics/db_latency`, { method: 'DELETE' });
  if (!res.ok && res.status !== 404) {
    throw new Error(`Failed to remove latency toxic: HTTP ${res.status} ${await res.text()}`);
  }
}

async function setDbLatency(latencyMs) {
  if (latencyMs > 0) {
    await removeDbLatency(); // clear any toxic left by a previous KEEP=1 run so POST can't 409
    const res = await fetch(`${TOXIPROXY_URL}/proxies/mariadb/toxics`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name: 'db_latency', type: 'latency', stream: 'downstream',
        attributes: { latency: latencyMs, jitter: 0 },
      }),
    });
    if (!res.ok) throw new Error(`Failed to add latency toxic: HTTP ${res.status} ${await res.text()}`);
    log(`DB latency toxic enabled: ${latencyMs}ms per round-trip`);
  } else {
    await removeDbLatency();
    log('DB latency toxic removed');
  }
}

async function main() {
  const db = await connectDb();
  try {
    // --- Phase 0: create the player_data row (offline) on server A ---
    const seedBot = await join(SERVER_A, BOT_NAME);
    await waitForPlayer(db, BOT_UUID, (p) => p && p.online === 1, 30_000,
      'seed: player_data row created on server A');
    seedBot.quit();
    await waitForPlayer(db, BOT_UUID, (p) => p && p.online === 0, 30_000,
      'seed: player offline after logout');
    log('Seed complete: player_data row exists, offline');

    // --- Phase 1: disconnect mid-sync, then race the revert against a move to server B ---
    await setDbLatency(DB_LATENCY_MS);
    try {
      const raceBot = await join(SERVER_A, BOT_NAME);
      await sleep(DISCONNECT_AFTER_MS);

      // Prove the disconnect lands before the sync task's online=1 write (otherwise the
      // revert window is not exercised and the test would be vacuous).
      const midFlight = await queryPlayer(db, BOT_UUID);
      if (!midFlight || midFlight.online !== 0) {
        throw new Error(
          `Race window not exercised: expected online=0 (sync still mid-flight) at disconnect, ` +
          `got ${JSON.stringify(midFlight)}. Raise DB_LATENCY_MS or lower DISCONNECT_AFTER_MS.`);
      }
      log('Disconnecting while the sync task is still mid-flight (online not yet set)');
      raceBot.quit();

      // The sync task now writes online=1 (last_server=A) — the ghost — and will revert it a
      // couple of round-trips later. Seeing online=1 proves the revert is imminent.
      await waitForPlayer(db, BOT_UUID, (p) => p && p.online === 1, SYNC_OBSERVE_MS,
        'server A sync wrote online=1 (its revert is about to fire)');

      // Simulate the player having opened a newer session on server B: B now owns the row.
      // A's pending revert must leave this alone.
      await db.query('UPDATE player_data SET last_server = ? WHERE uuid = ?', [SERVER_B_ID, BOT_UUID]);
      log(`Injected last_server=${SERVER_B_ID}: server B now owns the session (online still 1)`);

      // Watch the row while A's revert fires. Without the last_server guard, markOffline runs
      // `online=0 WHERE uuid` and clobbers B's session — catch that and fail. With the guard
      // it is a no-op, so online stays 1 for the whole window and we fall through to PASS.
      const deadline = Date.now() + CLOBBER_WATCH_MS;
      while (Date.now() < deadline) {
        const row = await queryPlayer(db, BOT_UUID);
        if (row && row.online === 0) {
          throw new Error(
            `CLOBBER: server A's stale revert overwrote server B's session (online=0). ` +
            `Expected the last_server guard to make the revert a no-op. Row: ${JSON.stringify(row)}`);
        }
        await sleep(500);
      }
      const after = await queryPlayer(db, BOT_UUID);
      if (!after || after.online !== 1 || after.last_server !== SERVER_B_ID) {
        throw new Error(`Unexpected final state: ${JSON.stringify(after)} (wanted online=1,last_server=${SERVER_B_ID})`);
      }
      log("Server B's session intact: server A's stale revert did not clobber online");
    } finally {
      await setDbLatency(0);
    }
  } finally {
    await db.end();
  }

  log('PASS: a stale disconnect-revert does not clobber a newer session on another server');
  process.exit(0);
}

startWatchdog(5 * 60_000);

main().catch(async (err) => {
  try { await setDbLatency(0); } catch (ignored) { /* already gone */ }
  console.error(`[e2e-owner] FAIL: ${err.stack || err}`);
  process.exit(1);
});
