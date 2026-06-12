'use strict';

/*
 * PlayerSync happy-path e2e test.
 *
 * Joins server A with a vanilla-protocol bot (possible because PlayerSync is a
 * server-side-only mod), gives the bot items and XP via RCON, disconnects, joins
 * server B with the same username (offline mode => same UUID) and asserts that
 * inventory and XP arrived through the shared database.
 *
 * Exit code 0 = pass, 1 = fail.
 */

const mineflayer = require('mineflayer');
const { Rcon } = require('rcon-client');

const BOT_NAME = 'SyncTester';
const VERSION = '1.20.1';
const HOST = process.env.MC_HOST || '127.0.0.1';
const SERVER_A = { name: 'server-a', port: 25565, rconPort: 25575 };
const SERVER_B = { name: 'server-b', port: 25566, rconPort: 25576 };
const RCON_PASSWORD = process.env.RCON_PASSWORD || 'e2e-rcon';

const DIAMONDS = 7;
const XP_POINTS = 100;
// Stored XP is recomputed from level+progress on save, so allow float rounding.
const XP_TOLERANCE = 2;

function log(msg) {
  console.log(`[e2e] ${new Date().toISOString()} ${msg}`);
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function countItem(bot, itemName) {
  return bot.inventory
    .items()
    .filter((item) => item.name === itemName)
    .reduce((sum, item) => sum + item.count, 0);
}

async function waitFor(description, timeoutMs, probe) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const value = probe();
    if (value !== undefined && value !== false) {
      return value;
    }
    if (Date.now() > deadline) {
      throw new Error(`Timed out after ${timeoutMs}ms waiting for: ${description}`);
    }
    await sleep(500);
  }
}

function join(server) {
  return new Promise((resolve, reject) => {
    log(`Joining ${server.name} (${HOST}:${server.port}) as ${BOT_NAME}`);
    const bot = mineflayer.createBot({
      host: HOST,
      port: server.port,
      username: BOT_NAME,
      version: VERSION,
      auth: 'offline',
    });
    bot.on('kicked', (reason) => reject(new Error(`Kicked from ${server.name}: ${JSON.stringify(reason)}`)));
    bot.on('error', (err) => reject(err));
    bot.on('end', (reason) => reject(new Error(`Connection to ${server.name} ended before spawn (${reason})`)));
    bot.once('spawn', () => {
      log(`Spawned on ${server.name}`);
      resolve(bot);
    });
  });
}

async function rcon(server, ...commands) {
  const conn = await Rcon.connect({ host: HOST, port: server.rconPort, password: RCON_PASSWORD });
  try {
    for (const command of commands) {
      const response = await conn.send(command);
      log(`rcon@${server.name} '${command}' -> ${response.trim() || '(no output)'}`);
    }
  } finally {
    await conn.end();
  }
}

// Fail loudly if anything stalls; without this a dropped connection can drain the
// event loop and let node exit 0 without ever reaching an assertion.
setTimeout(() => {
  console.error('[e2e] FAIL: global watchdog timeout (5 minutes) — test stalled');
  process.exit(1);
}, 5 * 60_000);

async function main() {
  // --- Phase 1: acquire state on server A ---
  const botA = await join(SERVER_A);
  // Give PlayerSync's async join handler time to register the new player
  // (insert into player_data) before we modify any state.
  await sleep(10_000);

  await rcon(
    SERVER_A,
    `give ${BOT_NAME} minecraft:diamond ${DIAMONDS}`,
    `xp add ${BOT_NAME} ${XP_POINTS} points`
  );

  await waitFor(`${DIAMONDS} diamonds in inventory on server A`, 30_000,
    () => countItem(botA, 'diamond') === DIAMONDS);
  log(`Server A state confirmed: ${countItem(botA, 'diamond')} diamonds, ${botA.experience.points} xp`);

  botA.quit();
  // Logout writes online=0 synchronously, then persists full state on a worker thread;
  // give it time to land.
  await sleep(10_000);

  // --- Phase 2: verify state arrived on server B ---
  const botB = await join(SERVER_B);

  await waitFor(`${DIAMONDS} diamonds in inventory on server B`, 30_000,
    () => countItem(botB, 'diamond') === DIAMONDS);

  await waitFor(`~${XP_POINTS} xp on server B`, 30_000,
    () => Math.abs(botB.experience.points - XP_POINTS) <= XP_TOLERANCE);

  log(`Server B state confirmed: ${countItem(botB, 'diamond')} diamonds, ${botB.experience.points} xp`);
  botB.quit();
  await sleep(2_000);

  log('PASS: inventory and XP synchronized from server A to server B');
  process.exit(0);
}

main().catch((err) => {
  console.error(`[e2e] FAIL: ${err.stack || err}`);
  process.exit(1);
});
