'use strict';

/*
 * A server the player has already left must not overwrite the session they moved to.
 *
 * Logging out runs two writes on the mod's bounded executor: `online=0` and then a full
 * `store()` of the player's inventory/armor/xp/... Both used to be scoped by uuid alone, so
 * whichever server got there last won. Hopping A -> B is exactly the case where "last" is the
 * wrong server: A queues its logout writes, B claims the row (online=1, last_server=B) and the
 * player keeps playing, and A's writes then land on top of a live session. The row flips to
 * online=0 while the player is on B (a ghost that defeats the already-online kick and lets two
 * sessions write one row), and B's inventory is replaced by A's stale copy — a rollback of
 * everything done since the hop, or a duplication of everything carried into it. The bounded
 * executor makes this likelier under load, not less: queued tasks wait for a free thread, so a
 * busy server's logout writes land later than anyone would guess.
 *
 * The fix scopes both writes to `AND last_server = <this server>`: the logout `online=0` goes
 * through markOffline (the compare-and-set the disconnect-during-sync revert already used), and
 * store()'s UPDATE reports how many rows it matched, logging a dropped save when a newer
 * session owns the row.
 *
 * Reproduction:
 *  1. Two sessions on server A (the first only inserts the row — the sync's new-player branch
 *     returns before adding player_synced), leaving A with 7 diamonds in memory and in the DB.
 *  2. A latency toxic on *server A's* database proxy, so A's logout writes take tens of
 *     seconds. server-b has its own proxy and keeps running at full speed.
 *  3. Quit A, then join B inside that window and give the session an 11-emerald marker, saved
 *     to the DB by /save-all.
 *  4. Wait for A's late writes to land. The row must still be B's: online=1, last_server=2, 11
 *     emeralds. Without the fix it turns into online=0 and/or A's 7 diamonds.
 *
 * Non-vacuity, in order:
 *   - server-b is recreated with kick_when_already_online disabled and the value is read back
 *     out of the container's own /data/config: with the kick enabled B refuses the hop join
 *     until A's writes have landed, which is precisely the race, so a config mount that failed
 *     to land cannot masquerade as a passing run;
 *   - a /save-all on A while A owns the row must reach the DB (the scoped UPDATE still writes
 *     in the normal case), so a green verdict cannot mean "saves never store anything here";
 *   - the hop is proven to happen inside the window: A's online=0 must not have landed before B
 *     claimed the row, else the scenario says so and asks for more latency;
 *   - B's marker must be in the DB *before* A's writes land, otherwise B's own save would be
 *     the last writer and would hide a clobber;
 *   - A must log that it dropped the save, twice (the logout store and the save-on-disconnect
 *     store), which proves A really did run its writes against the row this run — the logout's
 *     online=0 precedes its store, so a dropped store means the online=0 was attempted too.
 *
 * It runs last in the suite because it recreates server-b mid-run, and restores server-b's own
 * config before it finishes.
 *
 * Exit code 0 = pass, 1 = fail.
 */

const {
  SERVER_A, SERVER_B, sleep, countItem, waitFor, offlineUUID, connectDb, waitForPlayer,
  summarizeInventory, run, compose, composeFile, containerId, logCount, dataVolume,
  loadedModConfig, createHarness,
} = require('./lib');

const { log, join, rcon, rconAsk, setDbLatency, startWatchdog } = createHarness('e2e-hopclobber');

const KICKOFF_FILE = composeFile('docker-compose.kickoff.yml');

const SERVER_A_ID = 1; // e2e/config/server-a/playersync-common.toml
const SERVER_B_ID = 2; // e2e/config/server-b/playersync-common.toml

const BOT_NAME = 'HopClobberTester';
const BOT_UUID = offlineUUID(BOT_NAME);

const SEED_DIAMONDS = 5;    // first session on A
const CONTROL_DIAMONDS = 2; // added in the second session, to prove A's own save reaches the DB
const A_DIAMONDS = SEED_DIAMONDS + CONTROL_DIAMONDS; // what A's stale in-memory copy holds
const EMERALDS = 11;        // the marker the server B session writes

// Every JDBCsetUp call opens a fresh connection, and the MySQL handshake plus the driver's
// session setup, our explicit `USE`, and the statement itself are ~8 round-trips, each delayed
// once by the downstream toxic. So A's `online=0` lands roughly 8 x this after the quit (~30s)
// and its store(), on its own new connection, roughly twice that (~60s) — a comfortable window
// for the hop, which needs ~15s, without stalling the scenario for minutes.
const DB_LATENCY_MS = 4000;

const RECREATE_MS = 600_000;   // a recreate with a fresh /data regenerates the world
const CLAIM_MS = 90_000;       // server B's sync claiming the row after the join
const RESTORE_MS = 60_000;
const MARKER_MS = 90_000;
// A's two late stores land ~60s after the quit; be generous for a loaded machine.
const DRAIN_MS = 300_000;
const LOG_POLL_MS = 3_000;
// Once a clobber is seen, keep watching this long so the failure can name both of them.
const CLOBBER_GRACE_MS = 30_000;
// After the toxic is gone, anything of A's still in flight lands within a round-trip or two.
const SETTLE_MS = 45_000;
// doPlayerLogout's store and the save PlayerList.remove() fires right after it.
const EXPECTED_DROPS = 2;

const DROPPED_SAVE = `Dropping save for player ${BOT_UUID}`;

// One read of everything the assertions care about, so a clobber cannot slip between two
// queries and be reported half-seen.
async function rowState(db) {
  const [rows] = await db.query(
    'SELECT online, last_server, inventory FROM player_data WHERE uuid = ?', [BOT_UUID]);
  if (!rows.length) return null;
  // inventory is a mediumblob, so mysql2 hands back a Buffer.
  const blob = rows[0].inventory == null ? null : rows[0].inventory.toString('utf8');
  return { online: rows[0].online, last_server: rows[0].last_server, items: summarizeInventory(blob) };
}

const describe = (row) => (row
  ? `online=${row.online} last_server=${row.last_server} items=${JSON.stringify(row.items)}`
  : 'no row');

const hasMarker = (row) => !!row
  && row.items['minecraft:emerald'] === EMERALDS
  && !row.items['minecraft:diamond'];

// What went wrong with the row, in the words of the two clobbers, or null while it is intact.
function clobbers(row) {
  const found = [];
  if (!row) {
    return ['the player_data row disappeared'];
  }
  if (row.online !== 1) {
    found.push(`online=${row.online}: server A marked the player offline while they are playing `
      + `on server B (ghost state: the already-online kick no longer protects the session)`);
  }
  if (row.last_server !== SERVER_B_ID) {
    found.push(`last_server=${row.last_server}: the row no longer belongs to server B`);
  }
  if (!hasMarker(row)) {
    found.push(`items=${JSON.stringify(row.items)}: server B's ${EMERALDS}-emerald marker was `
      + `replaced${row.items['minecraft:diamond'] ? ` by server A's stale `
      + `${row.items['minecraft:diamond']} diamond(s)` : ''}`);
  }
  return found.length ? found : null;
}

// Recreates server-b with or without the kick-off config overlay and waits for it to be healthy.
// --no-deps keeps the database and toxiproxy out of the recreate; --renew-anon-volumes gives it
// an empty /data, which is what makes the config mount authoritative (itzg's config copy skips
// files that are newer in the destination, and Forge rewrites the config it loads).
async function recreateServerB(kickOff) {
  const args = ['up', '-d', '--no-deps', '--force-recreate', '--renew-anon-volumes',
    '--wait', '--wait-timeout', String(Math.round(RECREATE_MS / 1000)), 'server-b'];
  log(`Recreating server-b with ${kickOff ? 'the kick-off' : 'its own'} config...`);
  const orphaned = await dataVolume(await containerId('server-b'));
  await compose(args, { overlays: kickOff ? [KICKOFF_FILE] : [], timeoutMs: RECREATE_MS + 60_000 });
  const id = await containerId('server-b');
  // The volume the renewal left behind is attached to nothing and `down -v` will not collect it,
  // so drop the ~180MB here rather than leaking it once per recreate.
  if (orphaned) {
    await run('docker', ['volume', 'rm', orphaned])
      .catch((err) => log(`Could not remove the orphaned /data volume ${orphaned}: ${err.message}`));
  }
  const kick = await loadedModConfig(id, 'kick_when_already_online');
  const expected = kickOff ? 'false' : 'true';
  if (kick !== expected) {
    throw new Error(
      `server-b came up with kick_when_already_online=${kick}, expected ${expected}: the config ` +
      `mount did not take effect.` + (kickOff
        ? ' With the kick enabled server B refuses the hop join until server A\'s logout writes' +
          ' have landed, which is the very race this scenario is about.'
        : ''));
  }
  const serverId = Number(await loadedModConfig(id, 'Server_id'));
  if (serverId !== SERVER_B_ID) {
    throw new Error(`server-b came up as Server_id ${serverId}, expected ${SERVER_B_ID}`);
  }
  log(`server-b healthy (Server_id ${serverId}, kick_when_already_online=${kick}, `
    + `container ${id.slice(0, 12)})`);
  return id;
}

// The sync adds player_synced last, and doPlayerSaveToFile stores only a player carrying it.
async function waitForSyncTag(server, timeoutMs = 60_000) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const response = await rconAsk(server, `tag ${BOT_NAME} list`);
    if (response.includes('player_synced')) return response;
    if (Date.now() > deadline) {
      throw new Error(`player_synced was never added on ${server.name}: '${response}'`);
    }
    await sleep(2_000);
  }
}

// Fails with everything the row can tell us about the clobber, after a grace period so that a
// run which loses both the online flag and the inventory reports both rather than whichever
// landed first.
async function failClobbered(db, first, phase, dropped) {
  const firstSeen = clobbers(first);
  await sleep(CLOBBER_GRACE_MS);
  const last = await rowState(db);
  const lastSeen = clobbers(last) || [];
  const all = new Set([...firstSeen, ...lastSeen]);
  throw new Error(
    `CLOBBER: server A's late logout writes overwrote the session the player moved to ` +
    `(${phase}).\n` +
    [...all].map((line) => `  - ${line}`).join('\n') + '\n' +
    `  first seen:  ${describe(first)}\n` +
    `  ${Math.round(CLOBBER_GRACE_MS / 1000)}s later: ${describe(last)}\n` +
    `  expected:    online=1 last_server=${SERVER_B_ID} items={"minecraft:emerald":${EMERALDS}}\n` +
    `  server A logged '${DROPPED_SAVE}' ${dropped} time(s); with the writes scoped to ` +
    `last_server it should have dropped both of them.`);
}

async function main() {
  if (!process.env.PLAYERSYNC_JAR) {
    throw new Error(
      'PLAYERSYNC_JAR is not set; this scenario recreates server-b through docker compose and ' +
      'needs the same mod jar mount. Run the suite via e2e/run-e2e.sh.');
  }

  const serverA = await containerId('server-a');
  const db = await connectDb();
  let hopBot = null;
  try {
    // --- Phase 0: server B must accept a join while the row still says "online on A" ---
    await recreateServerB(true);

    // --- Phase 1: a real, fully synced session on server A ---
    // The first-ever session only inserts the player_data row: the sync's new-player branch
    // returns before adding player_synced, so a second session is what produces a synced one.
    const initBot = await join(SERVER_A, BOT_NAME);
    await waitForPlayer(db, BOT_UUID, (p) => p && p.online === 1, 30_000,
      'seed: player_data row created on server A');
    await rcon(SERVER_A, `give ${BOT_NAME} minecraft:diamond ${SEED_DIAMONDS}`);
    await waitFor(`${SEED_DIAMONDS} diamonds on server A`, 30_000,
      () => countItem(initBot, 'diamond') === SEED_DIAMONDS);
    initBot.quit();
    await waitForPlayer(db, BOT_UUID, (p) => p && p.online === 0, 30_000,
      'seed: player offline after leaving server A');

    const aBot = await join(SERVER_A, BOT_NAME);
    await waitFor(`${SEED_DIAMONDS} diamonds restored on server A`, RESTORE_MS,
      () => countItem(aBot, 'diamond') === SEED_DIAMONDS);
    await waitForSyncTag(SERVER_A);
    await rcon(SERVER_A, `give ${BOT_NAME} minecraft:diamond ${CONTROL_DIAMONDS}`);
    await waitFor(`${A_DIAMONDS} diamonds on server A`, 30_000,
      () => countItem(aBot, 'diamond') === A_DIAMONDS);

    // Positive control, and the pre-state the rest of the scenario is measured against: while A
    // owns the row its save must reach the DB. A green verdict below would otherwise be
    // consistent with saves never storing anything here.
    await rconAsk(SERVER_A, 'save-all');
    const owned = await waitFor(`server A's save stored ${A_DIAMONDS} diamonds`, 60_000,
      async () => {
        const row = await rowState(db);
        return row && row.items['minecraft:diamond'] === A_DIAMONDS
          && row.online === 1 && row.last_server === SERVER_A_ID ? row : null;
      });
    log(`Server A owns the session and its save reached the DB: ${describe(owned)}`);

    // --- Phase 2: slow server A's DB traffic only, then hop ---
    await setDbLatency(SERVER_A, DB_LATENCY_MS);
    try {
      aBot.quit();
      const atQuit = await rowState(db);
      if (!atQuit || atQuit.online !== 1 || atQuit.last_server !== SERVER_A_ID) {
        throw new Error(
          `Expected the row to still be server A's at the moment of the quit, got ` +
          `${describe(atQuit)}`);
      }
      log(`Quit server A; its online=0 and store() are now queued behind ${DB_LATENCY_MS}ms ` +
        `round-trips`);

      // Join B inside that window. B's own DB traffic is not slowed, so it claims the row and
      // finishes its sync in a second or two.
      hopBot = await join(SERVER_B, BOT_NAME);
      const claimDeadline = Date.now() + CLAIM_MS;
      for (;;) {
        const row = await rowState(db);
        if (row && row.online === 0) {
          throw new Error(
            `Hop window not exercised: server A's online=0 landed before server B claimed the ` +
            `row (${describe(row)}). The whole point is for A's writes to arrive after B owns ` +
            `the session — raise DB_LATENCY_MS.`);
        }
        if (row && row.online === 1 && row.last_server === SERVER_B_ID) {
          log(`Server B claimed the session while A's writes are still in flight: ${describe(row)}`);
          break;
        }
        if (Date.now() > claimDeadline) {
          throw new Error(`Server B did not claim the row within ${CLAIM_MS}ms: ${describe(row)}`);
        }
        await sleep(250);
      }

      await waitFor(`server B restored server A's ${A_DIAMONDS} diamonds`, RESTORE_MS,
        () => countItem(hopBot, 'diamond') === A_DIAMONDS);
      await waitForSyncTag(SERVER_B);

      // The marker: progress made on B after the hop. It has to be in the DB before A's writes
      // land, or B's own save would be the last writer and would mask the clobber.
      await rcon(SERVER_B, `clear ${BOT_NAME}`, `give ${BOT_NAME} minecraft:emerald ${EMERALDS}`);
      await waitFor(`${EMERALDS} emeralds and no diamonds on server B`, 30_000,
        () => countItem(hopBot, 'emerald') === EMERALDS && countItem(hopBot, 'diamond') === 0);
      await rconAsk(SERVER_B, 'save-all');
      const markerDeadline = Date.now() + MARKER_MS;
      for (;;) {
        const row = await rowState(db);
        if (row && row.online === 1 && row.last_server === SERVER_B_ID && hasMarker(row)) {
          log(`Marker stored by server B: ${describe(row)}`);
          break;
        }
        if (row && (row.online !== 1 || row.last_server !== SERVER_B_ID)) {
          await failClobbered(db, row, 'while server B was still storing its marker',
            await logCount(serverA, DROPPED_SAVE));
        }
        if (Date.now() > markerDeadline) {
          throw new Error(`Server B's marker never reached the DB within ${MARKER_MS}ms: ` +
            `${describe(row)}`);
        }
        await sleep(500);
      }

      // --- Phase 3: server A's late writes land ---
      // On a fixed build both of A's stores match 0 rows and say so, and its online=0 is the
      // same no-op silently. On an unfixed build the row is overwritten, which is caught here
      // the moment it happens rather than at the end of a fixed wait.
      const drainDeadline = Date.now() + DRAIN_MS;
      let dropped = 0;
      let nextLogPoll = 0;
      for (;;) {
        const row = await rowState(db);
        if (clobbers(row)) {
          await failClobbered(db, row, "while waiting for server A's late writes",
            await logCount(serverA, DROPPED_SAVE));
        }
        if (Date.now() >= nextLogPoll) {
          dropped = await logCount(serverA, DROPPED_SAVE);
          nextLogPoll = Date.now() + LOG_POLL_MS;
          if (dropped >= EXPECTED_DROPS) break;
        }
        if (Date.now() > drainDeadline) {
          throw new Error(
            `Server A's late logout writes never showed up: expected ${EXPECTED_DROPS} ` +
            `'${DROPPED_SAVE}' log line(s) within ${DRAIN_MS}ms, saw ${dropped}. The row is ` +
            `intact (${describe(row)}), but nothing proves A ran its writes against it, so this ` +
            `run would not have caught a clobber.`);
        }
        await sleep(500);
      }
      log(`Server A dropped ${dropped} save(s) for a session it no longer owns`);
    } finally {
      await setDbLatency(SERVER_A, 0);
    }

    // Anything of A's still in flight now lands at full speed; the row must survive that too.
    const settleDeadline = Date.now() + SETTLE_MS;
    while (Date.now() < settleDeadline) {
      const row = await rowState(db);
      if (clobbers(row)) {
        await failClobbered(db, row, 'after the toxic was removed',
          await logCount(serverA, DROPPED_SAVE));
      }
      await sleep(500);
    }
    const final = await rowState(db);
    log(`Server B's session survived server A's late writes: ${describe(final)}`);

    hopBot.quit();
    hopBot = null;
    await waitForPlayer(db, BOT_UUID, (p) => p && p.online === 0, 60_000,
      'player offline after leaving server B');

    // --- Phase 4: put server-b back and check the stack still works ---
    await recreateServerB(false);
    const restoredBot = await join(SERVER_B, BOT_NAME);
    try {
      await waitForPlayer(db, BOT_UUID,
        (p) => p && p.online === 1 && p.last_server === SERVER_B_ID, 60_000,
        `restored server-b owns the session (online=1, last_server=${SERVER_B_ID})`);
      await waitFor(`the ${EMERALDS}-emerald marker restored on the restored server-b`, RESTORE_MS,
        () => countItem(restoredBot, 'emerald') === EMERALDS);
    } finally {
      restoredBot.quit();
      await waitForPlayer(db, BOT_UUID, (p) => p && p.online === 0, 60_000,
        'player offline after leaving the restored server-b');
    }
    log('Restored server-b accepts a normal session and still has the marker');
  } finally {
    if (hopBot) hopBot.quit();
    await db.end();
  }

  log("PASS: a server the player has left does not clobber the session they moved to");
  process.exit(0);
}

startWatchdog(30 * 60_000);

main().catch(async (err) => {
  // Report first: restoring server-b below takes minutes, and the verdict is what matters.
  console.error(`[e2e-hopclobber] FAIL: ${err.stack || err}`);
  // Never leave the toxic or the kick-off config behind for the next scenario.
  try { await setDbLatency(SERVER_A, 0); } catch (ignored) { /* already gone */ }
  try {
    const kick = await loadedModConfig(await containerId('server-b'), 'kick_when_already_online');
    if (kick !== 'true') await recreateServerB(false);
  } catch (restoreError) {
    console.error(`[e2e-hopclobber] could not restore server-b's config: ` +
      `${restoreError.message || restoreError}`);
  }
  process.exit(1);
});
