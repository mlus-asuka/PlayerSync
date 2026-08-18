'use strict';

/*
 * A sync that throws for a *still-connected* player must not let a later logout or autosave
 * overwrite the player's good saved data.
 *
 * doPlayerJoin marks the player online=1 early, then restores armor/inventory/... from the
 * DB. If a restore step throws while the player is still connected and the catch path drops
 * the syncNotCompletedPlayer marker without setting player_synced, the next logout takes the
 * normal save path and persists the partially-restored (here: empty) inventory over the
 * player's real data — silent, permanent loss.
 *
 * We trigger the throw deterministically (no race): corrupt the `armor` column with a
 * non-numeric map key so StringToEntryMap's Integer.parseInt throws at restore, *before*
 * the inventory is restored. The good `inventory` column is left untouched, so the test can
 * assert it survives. The marker must outlive the failure so that logout refuses to save.
 * Otherwise the seeded diamonds are gone.
 *
 * Exit code 0 = pass, 1 = fail.
 */

const {
  SERVER_A, SERVER_B, sleep, countItem, waitFor,
  offlineUUID, connectDb, queryPlayer, waitForPlayer, createHarness,
} = require('./lib');

const { log, join, rcon, startWatchdog } = createHarness('e2e-dataloss');

const BOT_NAME = 'FailSyncTester';
const BOT_UUID = offlineUUID(BOT_NAME);
const DIAMONDS = 7;

// A map whose key is non-numeric: StringToEntryMap does Integer.parseInt("x") on it and
// throws. Length > 2 so the armor restore block actually runs. The value is empty NBT so
// nothing else about it matters.
const CORRUPT_ARMOR = '{x=B64:e30=}';

async function readColumns(db, uuid) {
  const [rows] = await db.query(
    'SELECT inventory, armor FROM player_data WHERE uuid = ?', [uuid]);
  if (!rows.length) return null;
  // inventory/armor are mediumblob, so mysql2 hands back Buffers. Normalize to strings so
  // callers compare by content (a Buffer !== Buffer reference check is always true).
  const asStr = (v) => (v == null ? v : v.toString('utf8'));
  return { inventory: asStr(rows[0].inventory), armor: asStr(rows[0].armor) };
}

async function main() {
  const db = await connectDb();
  try {
    // --- Phase 0: seed a known-good row (7 diamonds) on server A ---
    const seedBot = await join(SERVER_A, BOT_NAME);
    await waitForPlayer(db, BOT_UUID, (p) => p && p.online === 1, 30_000,
      'seed: player_data row created on server A');
    await rcon(SERVER_A, `give ${BOT_NAME} minecraft:diamond ${DIAMONDS}`);
    await waitFor('seed diamonds on server A', 30_000,
      () => countItem(seedBot, 'diamond') === DIAMONDS);
    seedBot.quit();
    await waitForPlayer(db, BOT_UUID, (p) => p && p.online === 0, 30_000,
      'seed: player offline after logout');
    const good = await readColumns(db, BOT_UUID);
    if (!good || !good.inventory) {
      throw new Error(`seed did not persist an inventory column: ${JSON.stringify(good)}`);
    }
    log(`Seed complete: ${DIAMONDS} diamonds persisted (inventory ${good.inventory.length} bytes)`);

    // --- Phase 1: corrupt armor so the next sync throws mid-restore (before inventory) ---
    await db.query('UPDATE player_data SET armor = ? WHERE uuid = ?', [CORRUPT_ARMOR, BOT_UUID]);
    log('Corrupted armor column; the next join sync will throw before restoring inventory');

    // Join server B, whose on-disk player file is empty for this player, so a failed restore
    // leaves an empty in-memory inventory — the thing a buggy logout would persist.
    const failBot = await join(SERVER_B, BOT_NAME);
    await waitForPlayer(db, BOT_UUID, (p) => p && p.online === 1, 30_000,
      'sync started on server B (online=1 written before the throw)');
    // Give the (latency-free) restore time to reach the armor parse and throw, then prove the
    // failure path really ran: inventory restore was skipped, so the bot has 0 diamonds. If it
    // had 7 the restore unexpectedly succeeded and this test would be vacuous.
    await sleep(3_000);
    if (countItem(failBot, 'diamond') !== 0) {
      throw new Error(
        `expected 0 diamonds after a failed restore (inventory step skipped), got ` +
        `${countItem(failBot, 'diamond')} — the sync did not throw where expected`);
    }
    log('Sync threw before inventory restore (bot has 0 diamonds), player still connected');

    failBot.quit();
    await waitForPlayer(db, BOT_UUID, (p) => p && p.online === 0, 30_000,
      'player offline after logout from server B');

    // --- Phase 2: the good data must be untouched ---
    const after = await readColumns(db, BOT_UUID);
    if (!after || after.inventory !== good.inventory) {
      throw new Error(
        `DATA LOSS: inventory column changed after a failed-sync logout.\n` +
        `  before: ${good.inventory}\n  after:  ${after && after.inventory}`);
    }
    log('Inventory column unchanged after the failed-sync logout');

    // End-to-end confirmation: undo the corruption and rejoin. The diamonds must restore.
    await db.query('UPDATE player_data SET armor = ? WHERE uuid = ?', [good.armor, BOT_UUID]);
    const verifyBot = await join(SERVER_B, BOT_NAME);
    await waitFor(`${DIAMONDS} diamonds restored on server B after the failed sync`, 30_000,
      () => countItem(verifyBot, 'diamond') === DIAMONDS);
    log(`Server B restored ${countItem(verifyBot, 'diamond')} diamonds — no data was lost`);
    verifyBot.quit();
    await waitForPlayer(db, BOT_UUID, (p) => p && p.online === 0, 30_000,
      'player offline after leaving server B');
  } finally {
    await db.end();
  }

  log('PASS: a sync that fails for a connected player does not overwrite good saved data');
  process.exit(0);
}

startWatchdog(5 * 60_000);

main().catch((err) => {
  console.error(`[e2e-dataloss] FAIL: ${err.stack || err}`);
  process.exit(1);
});
