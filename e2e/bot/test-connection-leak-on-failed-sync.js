'use strict';

/*
 * A sync that throws mid-restore must not leave its database connections behind.
 *
 * doPlayerJoin opens two JDBCsetUp.QueryResult handles and each one owns its own JDBC
 * connection. Closing them by hand only on the two normal exit paths means anything thrown in
 * the restore between the second query and those closes unwinds straight to the method's
 * catch, which repairs the player's state and never touches the handles: two live connections
 * per failed join, held until the driver's GC-driven cleanup thread happens to reap them. A
 * restore that throws on every join — bad data in the row, a datafixer failure, an item mod
 * dropped from one server — walks the server into max_connections.
 *
 * The failure is triggered in Java-land, with the database itself healthy: `armor` gets a
 * non-numeric map key, so StringToEntryMap's Integer.parseInt throws deep inside the restore
 * with both handles open. (Killing the DB connections instead would close the very connections
 * the leak is measured in.)
 *
 * Measurement: connections MariaDB holds for the mod's DB user, from the harness's own
 * connection, counting only ones parked (idle) long enough that the mod cannot be using them.
 * A login is allowed to park none of them, so doPlayerJoin's abandoned pair is the whole of
 * the signal; successful joins are measured too, to confirm the login path really settles back
 * to nothing in this build.
 *
 * Non-vacuity: every failed session first gets a fresh XP value written into its row. The
 * restore applies XP before it reaches armor, so the bot ending up with *this* session's XP
 * proves the restore ran, while the missing player_synced tag proves it never finished — it
 * threw in between, with both handles open. A final clean join is the positive control.
 *
 * Timing: leaked connections survive only until the driver reaps them a few seconds after a GC
 * (~8s, measured), so each session is measured right after its sync settles rather than
 * "eventually", and several failed joins keep one unlucky reap from hiding the leak.
 *
 * Exit code 0 = pass, 1 = fail.
 */

const {
  HOST, RCON_PASSWORD, SERVER_A, sleep, countItem, waitFor,
  offlineUUID, connectDb, waitForPlayer, createHarness,
} = require('./lib');
const { Rcon } = require('rcon-client');

const { log, join, rcon, startWatchdog } = createHarness('e2e-connleak');

const BOT_NAME = 'ConnLeakTester';
const BOT_UUID = offlineUUID(BOT_NAME);
const DB_USER = process.env.DB_USER || 'playersync';

const DIAMONDS = 4;
const HEALTHY_JOINS = 2; // reference sessions: a sync that completes must stay in the allowance
const FAILED_JOINS = 3;  // > 1 so a single unlucky reap cannot hide the leak
const CONTROL_XP = 4242; // restored by the final, uncorrupted join
const XP_TOLERANCE = 2;

// Connections a login may leave parked with nothing wrong: none. Every query the login path
// runs — doPlayerConnect's checks, doPlayerJoin's pair, onDataPackSyncEvent's advancements
// read — releases its connection on all exit paths, so a login that goes well settles back to
// zero and a failed restore's two abandoned handles are measured against a fixed number rather
// than against whatever the driver's reaper happened to have collected.
const LOGIN_PARK_ALLOWANCE = 0;

// A map whose key is not a number: StringToEntryMap runs Integer.parseInt("x") over it and
// throws. Length > 2 so the armor restore block actually runs, and the value is empty NBT
// because nothing else about it matters.
const CORRUPT_ARMOR = '{x=B64:e30=}';

// Only count connections idle at least this long. The mod opens a connection per query and
// closes it right after, so a connection it is really using sits idle for microseconds between
// statements; anything past this threshold is parked, not working.
const IDLE_MS = 500;
// How long each session's aftermath is watched, and how a plateau is told from a blip: a value
// counts only if it held for SUSTAIN_SAMPLES consecutive samples. Kept short because the
// driver's reaper eventually closes leaked connections.
const WATCH_MS = 6_000;
const SAMPLE_INTERVAL_MS = 250;
const SUSTAIN_SAMPLES = 4; // 1s

// Connections MariaDB currently holds for the mod's DB user. The mod and this test share that
// account, so PROCESSLIST lists them without the PROCESS privilege; our own connection is
// excluded by id.
async function parkedConnections(db) {
  const [rows] = await db.query(
    `SELECT ID, HOST, TIME_MS FROM information_schema.PROCESSLIST
      WHERE USER = ? AND ID <> CONNECTION_ID() AND COMMAND = 'Sleep' AND TIME_MS >= ?
      ORDER BY ID`,
    [DB_USER, IDLE_MS]);
  return rows;
}

// The highest count that held for SUSTAIN_SAMPLES consecutive samples: the level of a plateau
// rather than of a single blip. Formally the maximum, over every window of that many
// consecutive samples, of the window's minimum.
function sustainedPeak(series) {
  let peak = 0;
  for (let i = 0; i + SUSTAIN_SAMPLES <= series.length; i++) {
    peak = Math.max(peak, Math.min(...series.slice(i, i + SUSTAIN_SAMPLES)));
  }
  return peak;
}

// Watches the connections that appeared since `knownIds` was taken. Identifying them by id
// means connections parked before this session — and the reaper closing them mid-watch — cannot
// move the number either way.
async function watchNewConnections(db, knownIds) {
  const series = [];
  let peakRows = [];
  const deadline = Date.now() + WATCH_MS;
  for (;;) {
    const rows = (await parkedConnections(db)).filter((row) => !knownIds.has(row.ID));
    series.push(rows.length);
    if (rows.length > peakRows.length) peakRows = rows;
    if (Date.now() >= deadline) break;
    await sleep(SAMPLE_INTERVAL_MS);
  }
  return { peak: sustainedPeak(series), series, peakRows };
}

const describeRows = (rows) => (rows.length
  ? rows.map((row) => `#${row.ID} idle ${Math.round(Number(row.TIME_MS))}ms from ${row.HOST}`).join(', ')
  : 'none');

// lib's rcon() discards the reply text, and here the reply *is* the assertion.
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

// The sync adds player_synced as its very last step, so its presence means the whole restore
// ran and both handles reached their close.
async function waitForSyncTag(server, timeoutMs = 60_000) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const response = await rconAsk(server, `tag ${BOT_NAME} list`);
    if (response.includes('player_synced')) return response;
    if (Date.now() > deadline) {
      throw new Error(`player_synced was never added on ${server.name}: '${response}'`);
    }
    await sleep(1_000);
  }
}

async function readArmor(db) {
  const [rows] = await db.query('SELECT armor FROM player_data WHERE uuid = ?', [BOT_UUID]);
  // armor is a mediumblob, so mysql2 hands back a Buffer.
  return rows.length && rows[0].armor != null ? rows[0].armor.toString('utf8') : null;
}

// One session: join, wait for `outcome` to make the sync's result observable, watch what the
// session left parked, then hand the still-connected bot to `inspect`. The inspection runs
// after the measurement so its round-trips cannot eat into the watch window.
async function measureSession(db, server, outcome, inspect) {
  const knownIds = new Set((await parkedConnections(db)).map((row) => row.ID));
  const bot = await join(server, BOT_NAME);
  try {
    await outcome(bot);
    const watch = await watchNewConnections(db, knownIds);
    if (inspect) {
      await inspect(bot);
    }
    return watch;
  } finally {
    bot.quit();
    await waitForPlayer(db, BOT_UUID, (p) => p && p.online === 0, 30_000,
      `player offline after leaving ${server.name}`);
  }
}

async function main() {
  const db = await connectDb();
  let goodArmor = null;
  try {
    // --- Phase 0: seed a row worth restoring (diamonds, real armor) on server A ---
    const seedBot = await join(SERVER_A, BOT_NAME);
    await waitForPlayer(db, BOT_UUID, (p) => p && p.online === 1, 30_000,
      'seed: player_data row created on server A');
    await rcon(SERVER_A, `give ${BOT_NAME} minecraft:diamond ${DIAMONDS}`);
    await waitFor(`${DIAMONDS} seed diamonds on server A`, 30_000,
      () => countItem(seedBot, 'diamond') === DIAMONDS);
    seedBot.quit();
    await waitForPlayer(db, BOT_UUID, (p) => p && p.online === 0, 30_000,
      'seed: player offline after logout');
    goodArmor = await readArmor(db);
    if (!goodArmor) {
      throw new Error('seed did not persist an armor column, so it cannot be corrupted');
    }
    log(`Seed complete: ${DIAMONDS} diamonds persisted, armor column '${goodArmor}'`);

    try {
      // --- Phase 1: reference — a sync that completes stays inside the allowance ---
      // Every session below runs on the same server, so this first one pays the datafixer
      // warm-up: a restore still in flight holds its handles legitimately, and a slow one
      // would be indistinguishable from a leak.
      for (let session = 1; session <= HEALTHY_JOINS; session++) {
        const watch = await measureSession(db, SERVER_A, () => waitForSyncTag(SERVER_A));
        log(`Successful sync ${session}: ${watch.peak} connection(s) parked ` +
          `[series ${watch.series.join(',')}] (${describeRows(watch.peakRows)})`);
        if (watch.peak > LOGIN_PARK_ALLOWANCE) {
          throw new Error(
            `a *successful* sync parked ${watch.peak} connection(s), past the allowance of ` +
            `${LOGIN_PARK_ALLOWANCE} (${describeRows(watch.peakRows)}). The login path now holds ` +
            `more than this test assumes, so its verdict on failed syncs would mean nothing.`);
        }
      }
      log(`A successful sync parks no more than the allowed ${LOGIN_PARK_ALLOWANCE} connection(s)`);

      // --- Phase 2: make the restore throw, repeatedly ---
      await db.query('UPDATE player_data SET armor = ? WHERE uuid = ?', [CORRUPT_ARMOR, BOT_UUID]);
      log('Corrupted the armor column; parsing its map key now throws mid-restore');

      const failures = [];
      for (let session = 1; session <= FAILED_JOINS; session++) {
        // Fresh XP per session: the restore applies it before reaching armor, and the local
        // player file cannot hold a value written after it was saved, so seeing it proves
        // *this* session's restore ran.
        const xp = 100 * session + 7;
        await db.query('UPDATE player_data SET xp = ? WHERE uuid = ?', [xp, BOT_UUID]);
        const watch = await measureSession(
          db, SERVER_A,
          () => waitForPlayer(db, BOT_UUID, (p) => p && p.online === 1, 30_000,
            `failed sync ${session}: sync task reached its online=1 write`),
          async (bot) => {
            await waitFor(`failed sync ${session}: restore applied this session's ${xp} xp`, 30_000,
              () => Math.abs(bot.experience.points - xp) <= XP_TOLERANCE);
            const tags = await rconAsk(SERVER_A, `tag ${BOT_NAME} list`);
            if (tags.includes('player_synced')) {
              throw new Error(
                `failed sync ${session} completed instead of throwing (player_synced present) — ` +
                `the corrupted armor no longer aborts the restore, so this test is vacuous`);
            }
          });
        log(`Failed sync ${session}: ${watch.peak} connection(s) parked ` +
          `[series ${watch.series.join(',')}] (${describeRows(watch.peakRows)})`);
        failures.push(watch);
      }

      if (failures.some((watch) => watch.peak > LOGIN_PARK_ALLOWANCE)) {
        throw new Error(
          'CONNECTION LEAK: a sync that throws mid-restore leaves database connections behind.\n' +
          `  allowance: ${LOGIN_PARK_ALLOWANCE} parked connection(s) per login, as measured on the ` +
          'successful syncs above\n' +
          failures.map((watch, i) =>
            `  failed sync ${i + 1} parks ${watch.peak} [series ${watch.series.join(',')}]` +
            ` — ${describeRows(watch.peakRows)}`).join('\n') +
          '\n  a failed join is expected to hold two connections past the allowance:' +
          '\n  doPlayerJoin\'s qr1/qr2, abandoned when the restore throws past their closes');
      }
      log(`All ${FAILED_JOINS} failed syncs parked no more than the allowed ${LOGIN_PARK_ALLOWANCE} connection(s)`);
    } finally {
      await db.query('UPDATE player_data SET armor = ? WHERE uuid = ?', [goodArmor, BOT_UUID]);
    }

    // --- Phase 3: positive control — uncorrupted, the same join syncs to completion ---
    // Proves the corrupted armor is what aborted the restores above, and leaves the row and
    // the server's player file consistent for the scenarios that follow.
    await db.query('UPDATE player_data SET xp = ? WHERE uuid = ?', [CONTROL_XP, BOT_UUID]);
    const controlBot = await join(SERVER_A, BOT_NAME);
    try {
      await waitFor(`control: restore applied ${CONTROL_XP} xp and ${DIAMONDS} diamonds`, 60_000,
        () => Math.abs(controlBot.experience.points - CONTROL_XP) <= XP_TOLERANCE
          && countItem(controlBot, 'diamond') === DIAMONDS);
      await waitForSyncTag(SERVER_A);
      log(`Positive control: with armor restored the sync completed (${controlBot.experience.points} xp)`);
    } finally {
      controlBot.quit();
      await waitForPlayer(db, BOT_UUID, (p) => p && p.online === 0, 30_000,
        'player offline after the control session');
    }
  } finally {
    await db.end();
  }

  log('PASS: a sync that throws mid-restore releases its database connections');
  process.exit(0);
}

startWatchdog(8 * 60_000);

main().catch((err) => {
  console.error(`[e2e-connleak] FAIL: ${err.stack || err}`);
  process.exit(1);
});
