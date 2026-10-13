'use strict';

/*
 * Two servers must not both claim one player's session.
 *
 * The already-online check runs at negotiation time (doPlayerConnect) and the claim that acts on
 * it at login time (doPlayerJoin), as two tasks queued separately on the mod's executor, so the
 * window between reading `online` and writing it spans the whole login flow. The claim used to be
 * a blind `UPDATE player_data SET online=1,last_server=<me> WHERE uuid=?`, so two servers could
 * both read online=0, both pass the check, and both claim. The last writer then took a row whose
 * player was live on the other server, which is the two-session duplication the kick exists to
 * prevent. The fix makes the claim one conditional statement and refuses the login when it
 * matches no rows.
 *
 * A latency toxic on server A's database proxy stretches A's login to minutes while server B runs
 * unproxied. Bot X therefore spawns on A with A's claim still in flight, X' claims the row on B
 * under the same username, and A's claim then has to find the row owned and refuse its own login,
 * leaving X' and its marker untouched. Unfixed, A's blind claim flips last_server to 1 while X'
 * plays on the row.
 *
 * The checks along the way are calibration guards. If the timing drifts they fail saying what to
 * adjust, rather than passing on a race that never happened.
 *
 * Exit code 0 = pass, 1 = fail.
 */

const {
  SERVER_A, SERVER_B, countItem, waitFor, offlineUUID, connectDb, queryPlayer,
  waitForPlayer, dbInventory, containerId, logCount, trackSessionEnd, createHarness,
} = require('./lib');

const { log, join, rcon, rconAsk, setDbLatency, startWatchdog } = createHarness('e2e-claimrace');

const SERVER_A_ID = 1; // e2e/config/server-a/playersync-common.toml
const SERVER_B_ID = 2; // e2e/config/server-b/playersync-common.toml

const BOT_NAME = 'ClaimRaceTester';
const BOT_UUID = offlineUUID(BOT_NAME);

const EMERALDS = 11; // server B's marker, which A must not overwrite or take the row from

// ~28s per statement on server A, measured on this stack. Its negotiation read lands 28s after
// the connection, and its claim, the fourth statement of the login task, ~160s after.
const DB_LATENCY_MS = 4000;
// The mod's advancement query runs on the server thread, so A stops sending keepalives for a
// statement at a time and the client's default 30s watchdog would drop X mid-scenario.
const KEEPALIVE_MS = 600_000;
const SPAWN_MS = 180_000;     // X's login on A, held up by that server-thread query
const B_CLAIM_MS = 60_000;    // B claiming the row, unproxied and quick
const MARKER_MS = 90_000;
const LIST_MS = 90_000;       // rcon on A answers only between server-thread stalls
// A's claim, the lookup naming the owner, and the kick, about 7 statements after the join.
const OUTCOME_MS = 300_000;
const SETTLE_MS = 120_000;

// The row is B's. Both outcomes below are checked against it.
function ownedByB(row) {
  return row && row.online === 1 && row.last_server === SERVER_B_ID;
}

async function playerListed(server, timeoutMs) {
  const reply = await rconAsk(server, 'list', timeoutMs);
  return reply.includes(BOT_NAME);
}

async function main() {
  const db = await connectDb();
  const aId = await containerId('server-a');
  try {
    // --- Phase 0: an ordinary session, so the row exists and the datafixers are warm ---
    const warmUp = await join(SERVER_A, BOT_NAME);
    await waitForPlayer(db, BOT_UUID, (p) => p && p.online === 1, 60_000,
      'warm-up: row claimed by server A');
    warmUp.quit();
    await waitForPlayer(db, BOT_UUID, (p) => p && p.online === 0, 60_000,
      'warm-up: player offline again');
    log('Warm-up done: the row exists and is unclaimed');

    await setDbLatency(SERVER_A, DB_LATENCY_MS);
    let botA;
    let botB;
    try {
      // --- Phase 1: X joins A, its check passed and its claim still in flight ---
      const joinedAt = Date.now();
      botA = await join(SERVER_A, BOT_NAME, {
        timeoutMs: SPAWN_MS, keepAliveTimeoutMs: KEEPALIVE_MS,
      });
      const endA = trackSessionEnd(botA);
      log(`X spawned on server A after ${((Date.now() - joinedAt) / 1000).toFixed(1)}s: A's `
        + "negotiation read has landed and let it in, and A's claim is still in flight");

      // --- Phase 2: X' claims the row on B inside that window ---
      const beforeB = await queryPlayer(db, BOT_UUID);
      if (!beforeB || beforeB.online !== 0) {
        throw new Error(
          `Calibration: expected online=0 (server A's claim still in flight) before joining B, `
          + `got ${JSON.stringify(beforeB)}. A claimed sooner than measured, so raise DB_LATENCY_MS.`);
      }
      botB = await join(SERVER_B, BOT_NAME, { timeoutMs: 60_000 });
      const endB = trackSessionEnd(botB);
      await waitForPlayer(db, BOT_UUID, ownedByB, B_CLAIM_MS,
        `server B claimed the row (online=1,last_server=${SERVER_B_ID})`);
      log('Server B claimed the session while A was still mid-login');

      // B's marker has to be in the row before A's claim lands, or B writing last would hide a
      // steal. /save-all is B's own store(), which only writes while B owns the row.
      await rcon(SERVER_B, `give ${BOT_NAME} minecraft:emerald ${EMERALDS}`);
      await waitFor("X' holds the marker", 30_000, () => countItem(botB, 'emerald') === EMERALDS);
      await rconAsk(SERVER_B, 'save-all');
      await waitFor('the marker reached the row', MARKER_MS, async () => {
        const stored = await dbInventory(db, BOT_UUID);
        return stored && stored.items['minecraft:emerald'] === EMERALDS;
      });
      log(`Server B's ${EMERALDS}-emerald marker is in the row`);

      // Two live sessions on one uuid, the state the blind claim turns into a stolen row. The
      // negotiation check is fire-and-forget and cannot prevent it, so the claim must.
      if (endA.ended || endB.ended) {
        throw new Error(
          `Calibration: a session ended before server A's claim landed `
          + `(X: ${endA.reason || 'connected'}, X': ${endB.reason || 'connected'}).`);
      }
      for (const server of [SERVER_A, SERVER_B]) {
        if (!await playerListed(server, LIST_MS)) {
          throw new Error(
            `Calibration: ${server.name} does not list ${BOT_NAME}, so the two sessions are not `
            + 'live at the same time and the claim race is not being exercised.');
        }
      }
      log('Both servers list the player, so two live sessions share one row and A is about to claim');
      const refusalMarker = `server ${SERVER_B_ID} owns the session`;
      const refusalsBefore = await logCount(aId, refusalMarker);

      // --- Phase 3: A's claim lands. Either it steals the row, or it refuses the login ---
      const outcome = await waitFor("server A's claim to land or refuse", OUTCOME_MS, async () => {
        const row = await queryPlayer(db, BOT_UUID);
        if (row && row.last_server === SERVER_A_ID) return { stolen: row };
        if (endA.ended) return { refused: endA.reason };
        return null;
      });

      if (outcome.stolen) {
        const bothLive = !endA.ended && !endB.ended;
        throw new Error(
          `STOLEN: server A's claim took the row from server B while X' was playing on it, `
          + `${JSON.stringify(outcome.stolen)} (wanted online=1,last_server=${SERVER_B_ID}). `
          + `Sessions still connected: X ${endA.ended ? `ended (${endA.reason})` : 'yes'}, `
          + `X' ${endB.ended ? `ended (${endB.reason})` : 'yes'}`
          + (bothLive ? '. Two live sessions are writing one row.' : '.'));
      }

      // The refusal has to be the already-online one, not a dropped connection.
      log(`Server A ended X's session: ${outcome.refused}`);
      if (!/already_online|synchronization server/i.test(outcome.refused)) {
        throw new Error(
          `Server A ended X's session, but not with the already-online refusal: ${outcome.refused}`);
      }
      // Corroborate from A's side that the conditional claim refused it, and named the owner.
      if (await logCount(aId, refusalMarker) <= refusalsBefore) {
        throw new Error(
          'Server A refused the login without logging which server owns the session, so the '
          + 'refusal did not come from the conditional claim.');
      }
      if (endB.ended) {
        throw new Error(`Server B's session did not survive server A's refused claim: ${endB.reason}`);
      }
      if (countItem(botB, 'emerald') !== EMERALDS) {
        throw new Error(`X' lost its marker on server B: ${countItem(botB, 'emerald')} emeralds`);
      }
      const after = await queryPlayer(db, BOT_UUID);
      const storedAfter = await dbInventory(db, BOT_UUID);
      if (!ownedByB(after) || !storedAfter || storedAfter.items['minecraft:emerald'] !== EMERALDS) {
        throw new Error(
          `Server B's session was not left intact: row ${JSON.stringify(after)}, `
          + `stored items ${JSON.stringify(storedAfter && storedAfter.items)} `
          + `(wanted online=1,last_server=${SERVER_B_ID} with ${EMERALDS} emeralds)`);
      }
      log("Server A refused its own login and left server B's session and row alone");
    } finally {
      await setDbLatency(SERVER_A, 0);
      if (botA) botA.quit();
      if (botB) botB.quit();
    }

    // --- Phase 4: the stack is still usable ---
    await waitForPlayer(db, BOT_UUID, (p) => p && p.online === 0, SETTLE_MS,
      'the row is released once both sessions are gone');
    const normal = await join(SERVER_A, BOT_NAME, { timeoutMs: 60_000 });
    await waitForPlayer(db, BOT_UUID,
      (p) => p && p.online === 1 && p.last_server === SERVER_A_ID, 60_000,
      'a normal session on server A claims the row again');
    await waitFor("server B's marker restored on server A", 60_000,
      () => countItem(normal, 'emerald') === EMERALDS);
    normal.quit();
    await waitForPlayer(db, BOT_UUID, (p) => p && p.online === 0, 60_000,
      'and releases it again on logout');
    log('A normal session still works and carries the marker forward');
  } finally {
    await db.end();
  }

  log('PASS: a login whose claim loses the race is refused instead of stealing the session');
  process.exit(0);
}

startWatchdog(20 * 60_000);

main().catch(async (err) => {
  try { await setDbLatency(SERVER_A, 0); } catch (ignored) { /* already gone */ }
  console.error(`[e2e-claimrace] FAIL: ${err.stack || err}`);
  process.exit(1);
});
