'use strict';

/*
 * PlayerSync happy-path e2e test.
 *
 * Joins server A with a vanilla-protocol bot (possible because PlayerSync is a
 * server-side-only mod), gives the bot items and XP via RCON, disconnects, joins
 * server B with the same username (offline mode derives the same UUID from it)
 * and asserts that inventory and XP arrived through the shared database.
 *
 * Exit code 0 = pass, 1 = fail.
 */

const {
  SERVER_A, SERVER_B, countItem, waitFor,
  offlineUUID, connectDb, waitForPlayer, createHarness,
} = require('./lib');

const { log, join, verifyServerUuid, rcon, startWatchdog } = createHarness('e2e-sync');

const BOT_NAME = 'SyncTester';
const BOT_UUID = offlineUUID(BOT_NAME);
const DIAMONDS = 7;
const XP_POINTS = 100;
// Stored XP is recomputed from level+progress on save, so allow float rounding.
const XP_TOLERANCE = 2;

startWatchdog(5 * 60_000);

async function main() {
  const db = await connectDb();
  try {
    // --- Phase 1: acquire state on server A ---
    const botA = await join(SERVER_A, BOT_NAME);
    // The DB assertions key on the locally derived offline UUID. Confirm it matches the
    // UUID the server assigned, or every player_data lookup below silently misses its row.
    verifyServerUuid(botA, BOT_UUID);
    // Wait for PlayerSync's async join handler to register the new player in player_data
    // before modifying state. Polling the DB avoids guessing a sleep duration.
    await waitForPlayer(db, BOT_UUID, (p) => p && p.online === 1, 30_000,
      'player_data row created and online on server A');

    await rcon(
      SERVER_A,
      `give ${BOT_NAME} minecraft:diamond ${DIAMONDS}`,
      `xp add ${BOT_NAME} ${XP_POINTS} points`
    );

    await waitFor(`${DIAMONDS} diamonds in inventory on server A`, 30_000,
      () => countItem(botA, 'diamond') === DIAMONDS);
    log(`Server A state confirmed: ${countItem(botA, 'diamond')} diamonds, ${botA.experience.points} xp`);

    botA.quit();
    // Logout writes online=0 and then persists full state on a worker thread. Poll the DB
    // until the player is offline and the XP has landed, asserting the persisted row
    // directly rather than only observing the client.
    await waitForPlayer(db, BOT_UUID,
      (p) => p && p.online === 0 && Math.abs(p.xp - XP_POINTS) <= XP_TOLERANCE, 30_000,
      `player_data persisted on logout (offline, ~${XP_POINTS} xp)`);

    // --- Phase 2: verify state arrived on server B ---
    const botB = await join(SERVER_B, BOT_NAME);

    await waitFor(`${DIAMONDS} diamonds in inventory on server B`, 30_000,
      () => countItem(botB, 'diamond') === DIAMONDS);

    await waitFor(`~${XP_POINTS} xp on server B`, 30_000,
      () => Math.abs(botB.experience.points - XP_POINTS) <= XP_TOLERANCE);

    log(`Server B state confirmed: ${countItem(botB, 'diamond')} diamonds, ${botB.experience.points} xp`);
    botB.quit();
    // Confirm the logout persisted (online=0) rather than sleeping a guessed duration.
    await waitForPlayer(db, BOT_UUID, (p) => p && p.online === 0, 30_000,
      'player offline after leaving server B');
  } finally {
    await db.end();
  }

  log('PASS: inventory and XP synchronized from server A to server B');
  process.exit(0);
}

main().catch((err) => {
  console.error(`[e2e-sync] FAIL: ${err.stack || err}`);
  process.exit(1);
});
