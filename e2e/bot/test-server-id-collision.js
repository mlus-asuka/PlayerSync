'use strict';

/*
 * Two servers sharing one Server_id must be detected and reported.
 *
 * Every ownership check in the mod keys off Server_id, so a cloned config that duplicates it
 * turns them all into silent no-ops. server-b is recreated with server-a's id, and both servers
 * must report the collision, keep reporting it, and fall silent once the ids differ again.
 *
 * Runs at the end of the suite, because it recreates server-b and restores it only on a pass.
 *
 * Exit code 0 = pass, 1 = fail.
 */

const {
  SERVER_B, sleep, waitFor, offlineUUID, connectDb, waitForPlayer,
  run, compose, composeFile, containerId, logCount, dataVolume, loadedModConfig, createHarness,
} = require('./lib');

const { log, join, startWatchdog } = createHarness('e2e-serverid');

const COLLISION_FILE = composeFile('docker-compose.collision.yml');

const SERVER_A_ID = 1; // e2e/config/server-a/playersync-common.toml
const SERVER_B_ID = 2; // e2e/config/server-b/playersync-common.toml

// Substrings of the mod's messages. The boot-time hint is kept distinct from the heartbeat's
// report, because a restart inside the liveness window legitimately produces the hint.
const COLLISION_ERROR = 'Server_id collision detected';
const BOOT_SOFT_CHECK = 'may already be using this id';
const claimWarn = (id) => `Claiming Server_id ${id} in the shared database`;

// The heartbeat runs every 1800 LevelTickEvents, i.e. every ~15s of wall clock. These are
// generous multiples of that so a loaded machine cannot fail the test by ticking slowly.
const DETECT_MS = 180_000;
const REPEAT_MS = 120_000;
// After the restore, let any in-flight detection land, then require silence for this long.
const RESTORE_SETTLE_MS = 30_000;
const SILENCE_MS = 60_000;
// A recreate with a fresh /data regenerates the world, which takes minutes on a loaded machine.
const RECREATE_MS = 600_000;

const BOT_NAME = 'ServerIdTester';
const BOT_UUID = offlineUUID(BOT_NAME);

// --renew-anon-volumes gives server-b an empty /data, which makes the config mount authoritative.
// Forge rewrites the config it loads, and itzg's copy skips files newer in the destination.
async function recreateServerB(collide, expectedId) {
  const args = ['up', '-d', '--no-deps', '--force-recreate', '--renew-anon-volumes',
    '--wait', '--wait-timeout', String(Math.round(RECREATE_MS / 1000)), 'server-b'];
  log(`Recreating server-b with ${collide ? 'the colliding' : 'its own'} config (Server_id ${expectedId})...`);
  const orphaned = await dataVolume(await containerId('server-b'));
  await compose(args, {
    overlays: collide ? [COLLISION_FILE] : [],
    timeoutMs: RECREATE_MS + 60_000,
  });
  const id = await containerId('server-b');
  // The renewed volume is attached to nothing and `down -v` will not collect its ~180MB.
  if (orphaned) {
    await run('docker', ['volume', 'rm', orphaned])
      .catch((err) => log(`Could not remove the orphaned /data volume ${orphaned}: ${err.message}`));
  }
  const loaded = Number(await loadedModConfig(id, 'Server_id'));
  if (loaded !== expectedId) {
    throw new Error(
      `server-b came up with Server_id ${loaded}, expected ${expectedId}. The config mount did ` +
      `not take effect, so this run would prove nothing about collision detection`);
  }
  log(`server-b healthy, running as Server_id ${loaded} (container ${id.slice(0, 12)})`);
  return id;
}

async function main() {
  if (!process.env.PLAYERSYNC_JAR) {
    throw new Error(
      'PLAYERSYNC_JAR is not set. This scenario recreates server-b through docker compose and ' +
      'needs the same mod jar mount, so run the suite via e2e/run-e2e.sh.');
  }

  const serverA = await containerId('server-a');
  let serverB = await containerId('server-b');

  // --- Phase 1: a correctly configured stack reports no collision ---
  for (const [name, id] of [['server-a', serverA], ['server-b', serverB]]) {
    const count = await logCount(id, COLLISION_ERROR);
    if (count > 0) {
      throw new Error(
        `${name} already reports '${COLLISION_ERROR}' ${count} time(s) with distinct Server_ids ` +
        `configured. Detection fires on a healthy stack, so its verdict below would be worthless.`);
    }
  }
  log(`Healthy stack: neither server reports '${COLLISION_ERROR}'`);

  // --- Phase 2: give server-b server-a's id ---
  serverB = await recreateServerB(true, SERVER_A_ID);

  // --- Phase 3: both servers must notice, and keep noticing ---
  const counts = async () => ({
    a: await logCount(serverA, COLLISION_ERROR),
    b: await logCount(serverB, COLLISION_ERROR),
  });
  try {
    await waitFor(`both servers to report '${COLLISION_ERROR}'`, DETECT_MS, async () => {
      const seen = await counts();
      return seen.a > 0 && seen.b > 0;
    });
  } catch (err) {
    const seen = await counts();
    throw new Error(
      `UNDETECTED COLLISION: server-a and server-b are both running as Server_id ${SERVER_A_ID} ` +
      `and neither reported it (server-a ${seen.a}, server-b ${seen.b} occurrences of ` +
      `'${COLLISION_ERROR}' in ${DETECT_MS}ms). Every ownership guard is a no-op in this state, ` +
      `and nothing in the logs says why. Expected each heartbeat to find a foreign boot_token in ` +
      `server_info row ${SERVER_A_ID} and report it. (${err.message})`);
  }
  const detected = await counts();
  log(`Collision reported by both servers (server-a ${detected.a}, server-b ${detected.b})`);

  // The collision persists, so the complaint has to. A single report at boot would not do.
  await waitFor(`'${COLLISION_ERROR}' to repeat`, REPEAT_MS, async () => {
    const seen = await counts();
    return seen.a >= 2 || seen.b >= 2;
  });
  const repeated = await counts();
  log(`Collision report repeats while the collision lasts (server-a ${repeated.a}, server-b ${repeated.b})`);

  // server-b booted into a row server-a is actively heartbeating, so the cheap boot-time hint
  // has to have flagged it too.
  if (await logCount(serverB, BOOT_SOFT_CHECK) === 0) {
    throw new Error(
      `server-b booted into Server_id ${SERVER_A_ID}, whose row server-a updates every few ` +
      `seconds, without logging the boot-time hint ('${BOOT_SOFT_CHECK}')`);
  }
  log('server-b also flagged the live row at boot');

  // --- Phase 4: restore server-b's own id, the errors must stop ---
  serverB = await recreateServerB(false, SERVER_B_ID);
  // One last report right after the restore is legitimate. Whichever server wrote its token last
  // during the collision, the other still has to notice it once, so let that land first.
  await sleep(RESTORE_SETTLE_MS);
  const settled = await counts();
  await sleep(SILENCE_MS);
  const after = await counts();
  if (after.a !== settled.a || after.b !== settled.b) {
    throw new Error(
      `Collision still reported ${SILENCE_MS}ms after server-b went back to Server_id ` +
      `${SERVER_B_ID} (server-a ${settled.a} -> ${after.a}, server-b ${settled.b} -> ${after.b})`);
  }
  log(`Collision reports stopped once the ids were distinct again (server-a ${after.a}, server-b ${after.b})`);

  // The server that was recreated twice still accepts a player.
  const db = await connectDb();
  try {
    const bot = await join(SERVER_B, BOT_NAME);
    try {
      await waitForPlayer(db, BOT_UUID, (p) => p && p.online === 1 && p.last_server === SERVER_B_ID,
        60_000, `restored server-b owns the session (online=1, last_server=${SERVER_B_ID})`);
    } finally {
      bot.quit();
      await waitForPlayer(db, BOT_UUID, (p) => p && p.online === 0, 30_000,
        'player offline after leaving the restored server-b');
    }
  } finally {
    await db.end();
  }
  log('Restored stack accepts a normal session');

  // The boot warning naming the resolved id, on the two healthy-config boots we have.
  for (const [name, id, serverId] of [['server-a', serverA, SERVER_A_ID], ['server-b', serverB, SERVER_B_ID]]) {
    if (await logCount(id, claimWarn(serverId)) === 0) {
      throw new Error(
        `${name} never logged the id it claimed ('${claimWarn(serverId)}'), so an operator has ` +
        'no way to see which Server_id a server resolved');
    }
  }
  log(`Both servers log the Server_id they claim (${SERVER_A_ID}, ${SERVER_B_ID})`);

  log('PASS: two servers sharing a Server_id are detected and keep reporting it');
  process.exit(0);
}

startWatchdog(25 * 60_000);

main().catch((err) => {
  console.error(`[e2e-serverid] FAIL: ${err.stack || err}`);
  process.exit(1);
});
