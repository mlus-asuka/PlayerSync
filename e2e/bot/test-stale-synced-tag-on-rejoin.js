'use strict';

/*
 * A returning player must not still carry the previous session's player_synced tag.
 *
 * doPlayerSaveToFile only stores a player whose entity carries the player_synced tag, which
 * the sync adds once it has completed. Entity tags live in the player NBT, so they outlive
 * the session: a player who synced on this server once comes back with the tag already in
 * their .dat file. Until the new session's sync completes the server holds the *local*
 * .dat state, so any world save in that window (/save-all, autosave) passes the gate and
 * stores that stale pre-sync state over whatever the player has since done elsewhere.
 *
 * Reproduction:
 *  1. Two sessions on server A: the first only inserts the player_data row (the sync's
 *     new-player branch returns before adding the tag), the second is a real sync and adds
 *     it. Take 5 diamonds; A's player file ends up holding them plus the player_synced tag
 *     (asserted via /tag list while still online, so the stale tag this test depends on is
 *     a fact, not an assumption).
 *  2. Session on server B: clear the inventory, take 11 emeralds, quit. The DB row now
 *     holds the emerald marker — progress made elsewhere — and A's player file is stale.
 *  3. Rejoin A behind a toxiproxy latency toxic so the sync stays pending for tens of
 *     seconds, and /save-all repeatedly while it is.
 *
 * The DB row must keep the emerald marker for the whole pending window. With the stale tag
 * still in place the gate opens and the row is overwritten with A's 5 diamonds. A positive
 * control afterwards proves the storing machinery is live here at all: with the sync done
 * (tag legitimately re-added) a save does reach the DB.
 *
 * Exit code 0 = pass, 1 = fail.
 */

const {
  HOST, RCON_PASSWORD, SERVER_A, SERVER_B, sleep, countItem, waitFor,
  offlineUUID, connectDb, waitForPlayer, createHarness,
} = require('./lib');
const { Rcon } = require('rcon-client');

const { log, join, rcon, startWatchdog } = createHarness('e2e-staletag');

const BOT_NAME = 'StaleTagTester';
const BOT_UUID = offlineUUID(BOT_NAME);
const TOXIPROXY_URL = process.env.TOXIPROXY_URL || 'http://127.0.0.1:8474';

const DIAMONDS = 5;   // stale state left behind in server A's player file
const EMERALDS = 11;  // marker written to the DB by the server B session

// ~2s per DB round-trip, as in the other latency scenarios. Every query opens its own
// connection, so the rejoin sync stays pending for tens of seconds — long enough to drive
// several world saves through the gate.
const DB_LATENCY_MS = 2000;
// Upper bound on that pending window; the watch loop leaves as soon as the sync lands.
const PENDING_WATCH_MS = 180_000;
const SAVE_ALL_INTERVAL_MS = 10_000;

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

// lib's rcon() discards the reply text and uses rcon-client's thin 2s default timeout. Used
// here only where the reply is the assertion (/tag list) or where the server thread may be
// mid-query on the latency-toxic'd DB and answer late (/save-all).
async function rconAsk(server, command, timeoutMs = 20_000) {
  const conn = await Rcon.connect({
    host: HOST, port: server.rconPort, password: RCON_PASSWORD, timeout: timeoutMs,
  });
  try {
    const response = (await conn.send(command)).trim();
    log(`rcon@${server.name} '${command}' -> ${response || '(no output)'}`);
    return response;
  } finally {
    await conn.end().catch(() => { /* already closed */ });
  }
}

// The sync adds player_synced as its last step, so poll for it rather than assume it landed.
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

// A world save fires PlayerEvent.SaveToFile for every connected player. Retry: the mod runs
// some of its queries on the server thread, so under the toxic an rcon round-trip can time
// out even though the server is healthy.
async function saveAll(server) {
  for (let attempt = 1; attempt <= 3; attempt++) {
    try {
      await rconAsk(server, 'save-all');
      return true;
    } catch (err) {
      log(`save-all attempt ${attempt} failed: ${err.message || err}`);
      await sleep(1_000);
    }
  }
  return false;
}

// The mod stores the inventory as Java's HashMap#toString of {slot=serialized-nbt}, one
// entry per main-inventory slot, each value base64 of the stack's SNBT
// (use_legacy_serialization is off in the e2e config). Summarize it as {itemId: count} so
// assertions read items instead of an opaque blob.
function summarizeInventory(blob) {
  const summary = {};
  const open = blob ? blob.indexOf('{') : -1;
  const close = blob ? blob.lastIndexOf('}') : -1;
  if (open < 0 || close <= open) return summary;
  for (const entry of blob.slice(open + 1, close).split(',')) {
    const equalIndex = entry.indexOf('=');
    if (equalIndex < 0) continue;
    const value = entry.slice(equalIndex + 1).trim();
    if (!value.startsWith('B64:')) continue;
    const snbt = Buffer.from(value.slice(4), 'base64').toString('utf8');
    const id = /id:"([^"]+)"/.exec(snbt);
    if (!id) continue; // empty slot, serialized as "{}"
    const count = /Count:(\d+)b/.exec(snbt);
    summary[id[1]] = (summary[id[1]] || 0) + (count ? Number(count[1]) : 0);
  }
  return summary;
}

async function dbInventory(db, uuid) {
  const [rows] = await db.query('SELECT inventory FROM player_data WHERE uuid = ?', [uuid]);
  if (!rows.length) return null;
  // inventory is a mediumblob, so mysql2 hands back a Buffer.
  const blob = rows[0].inventory == null ? null : rows[0].inventory.toString('utf8');
  return { blob, items: summarizeInventory(blob) };
}

const isMarker = (items) => items['minecraft:emerald'] === EMERALDS && !items['minecraft:diamond'];

async function main() {
  const db = await connectDb();
  let rejoinBot = null;
  try {
    // --- Phase 0: leave diamonds *and* the player_synced tag in server A's player file ---
    // The first-ever session only inserts the player_data row: the sync's new-player branch
    // returns before adding the tag.
    const initBot = await join(SERVER_A, BOT_NAME);
    await waitForPlayer(db, BOT_UUID, (p) => p && p.online === 1, 30_000,
      'seed: player_data row created on server A');
    await rcon(SERVER_A, `give ${BOT_NAME} minecraft:diamond ${DIAMONDS}`);
    await waitFor(`${DIAMONDS} diamonds on server A`, 30_000,
      () => countItem(initBot, 'diamond') === DIAMONDS);
    initBot.quit();
    await waitForPlayer(db, BOT_UUID, (p) => p && p.online === 0, 30_000,
      'seed: player offline after leaving server A');
    const stale = await waitFor('server A session persisted its diamonds', 30_000, async () => {
      const inv = await dbInventory(db, BOT_UUID);
      return inv && inv.items['minecraft:diamond'] === DIAMONDS ? inv : null;
    });

    // A second session syncs the now-existing row, which is what adds the tag the save gate
    // checks. Quitting writes it into A's player file next to the diamonds — the stale pair
    // the rejoin below must not trust.
    const tagBot = await join(SERVER_A, BOT_NAME);
    await waitFor(`${DIAMONDS} diamonds restored on server A`, 60_000,
      () => countItem(tagBot, 'diamond') === DIAMONDS);
    await waitForSyncTag(SERVER_A);
    tagBot.quit();
    await waitForPlayer(db, BOT_UUID, (p) => p && p.online === 0, 30_000,
      'seed: player offline after the tagged session on server A');
    log(`Server A stored ${JSON.stringify(stale.items)}; its player file carries them plus player_synced`);

    // --- Phase 1: progress made elsewhere — server B replaces the DB row with the marker ---
    const botB = await join(SERVER_B, BOT_NAME);
    await waitFor(`server B restored the ${DIAMONDS} diamonds`, 60_000,
      () => countItem(botB, 'diamond') === DIAMONDS);
    await rcon(SERVER_B, `clear ${BOT_NAME}`, `give ${BOT_NAME} minecraft:emerald ${EMERALDS}`);
    await waitFor(`${EMERALDS} emeralds and no diamonds on server B`, 30_000,
      () => countItem(botB, 'emerald') === EMERALDS && countItem(botB, 'diamond') === 0);
    botB.quit();
    await waitForPlayer(db, BOT_UUID, (p) => p && p.online === 0, 30_000,
      'player offline after leaving server B');
    const marker = await waitFor('emerald marker persisted by server B', 30_000, async () => {
      const inv = await dbInventory(db, BOT_UUID);
      return inv && isMarker(inv.items) ? inv : null;
    });
    log(`Marker persisted: ${JSON.stringify(marker.items)} — server A's player file is now stale`);

    // --- Phase 2: rejoin A and save the world while the sync is still pending ---
    await setDbLatency(DB_LATENCY_MS);
    let saves = 0;
    try {
      rejoinBot = await join(SERVER_A, BOT_NAME);
      // Non-vacuity: what the server holds right now is A's stale player file, not the DB
      // row. Exactly the state a save in this window must not persist.
      await waitFor("server A loaded its stale player file (pre-sync state)", 30_000,
        () => countItem(rejoinBot, 'diamond') === DIAMONDS);
      if (countItem(rejoinBot, 'emerald') !== 0) {
        throw new Error(
          `Window not exercised: the sync already restored the marker before the first save. ` +
          `Raise DB_LATENCY_MS.`);
      }
      // Diagnostic: shows whether the previous session's tag came back with the player.
      await rconAsk(SERVER_A, `tag ${BOT_NAME} list`);

      const deadline = Date.now() + PENDING_WATCH_MS;
      let nextSave = 0;
      let synced = false;
      while (Date.now() < deadline) {
        const inv = await dbInventory(db, BOT_UUID);
        if (!inv || !isMarker(inv.items)) {
          throw new Error(
            `DATA LOSS: the DB row was overwritten while the rejoin sync was still pending ` +
            `(${saves} world save(s) triggered).\n` +
            `  expected marker: ${JSON.stringify(marker.items)}\n` +
            `  found:           ${JSON.stringify(inv && inv.items)}\n` +
            `  server A's stale player file held: ${JSON.stringify(stale.items)}`);
        }
        if (countItem(rejoinBot, 'emerald') === EMERALDS) {
          synced = true;
          break;
        }
        if (Date.now() >= nextSave) {
          // Repeat: the mod's pool runs two tasks at a time and one is the sync itself, so a
          // single save could be picked up only after the sync had finished.
          if (await saveAll(SERVER_A)) saves++;
          nextSave = Date.now() + SAVE_ALL_INTERVAL_MS;
        }
        await sleep(500);
      }
      if (!saves) {
        throw new Error('No world save could be triggered while the sync was pending');
      }
      if (!synced) {
        throw new Error(`Rejoin sync did not complete within ${PENDING_WATCH_MS}ms`);
      }
      log(`Marker survived ${saves} world save(s) during the pending sync, which then restored ${EMERALDS} emeralds`);
    } finally {
      await setDbLatency(0);
    }

    // --- Phase 3: positive control — with the sync done, a save does reach the DB ---
    // Otherwise a green Phase 2 could just mean saves never store anything in this setup.
    // The tag must also be back: this session's own sync is entitled to it.
    await waitForSyncTag(SERVER_A);
    await rcon(SERVER_A, `give ${BOT_NAME} minecraft:diamond 1`);
    await waitFor('control diamond in the rejoined session', 30_000,
      () => countItem(rejoinBot, 'diamond') === 1);
    const controlDeadline = Date.now() + 90_000;
    let control;
    for (;;) {
      await saveAll(SERVER_A);
      await sleep(2_000); // the store runs off the server thread
      control = await dbInventory(db, BOT_UUID);
      if (control && control.items['minecraft:diamond'] === 1) break;
      if (Date.now() > controlDeadline) {
        throw new Error(
          `Positive control failed: saving the synced session never reached the DB ` +
          `(row: ${JSON.stringify(control && control.items)})`);
      }
      await sleep(3_000);
    }
    log(`Positive control: saving the synced session stored ${JSON.stringify(control.items)}`);
    rejoinBot.quit();
    await waitForPlayer(db, BOT_UUID, (p) => p && p.online === 0, 30_000,
      'player offline after leaving server A');
  } finally {
    await db.end();
  }

  log('PASS: a stale player_synced tag does not let a pre-sync world save overwrite the database');
  process.exit(0);
}

startWatchdog(10 * 60_000);

main().catch(async (err) => {
  try { await setDbLatency(0); } catch (ignored) { /* already gone */ }
  console.error(`[e2e-staletag] FAIL: ${err.stack || err}`);
  process.exit(1);
});
