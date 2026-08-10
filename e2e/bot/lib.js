'use strict';

/*
 * Shared helpers for the e2e bots. `createHarness(tag)` returns connection/RCON
 * helpers bound to a log prefix. The stateless utilities and the server constants
 * are exported directly.
 */

const crypto = require('crypto');
const mineflayer = require('mineflayer');
const { Rcon } = require('rcon-client');
const mysql = require('mysql2/promise');

const VERSION = '1.20.1';
const HOST = process.env.MC_HOST || '127.0.0.1';
const SERVER_A = { name: 'server-a', port: 25565, rconPort: 25575 };
const SERVER_B = { name: 'server-b', port: 25566, rconPort: 25576 };
const RCON_PASSWORD = process.env.RCON_PASSWORD || 'e2e-rcon';

// Minecraft reports a failed command as ordinary response text on an otherwise successful
// send, so a silently no-op'd give/xp would only surface later as an unrelated timeout.
// Match the vanilla error phrasings only: successful output legitimately contains player
// and item names (`give` echoes "Gave 7 [Diamond] to <name>").
const RCON_ERROR_PATTERNS = [
  /Unknown or incomplete command/i,
  /Incorrect argument for command/i,
  /No targets matched selector/i,
  /That player does not exist/i,
  /No player was found/i,
];

// Marks the error a bounded join() rejects with when the login neither spawns nor fails.
const JOIN_TIMEOUT = 'JOIN_TIMEOUT';

// The mod's MariaDB as published straight to the host by docker-compose.yml. Assertions
// deliberately bypass toxiproxy so injected faults never slow or perturb what a test
// observes.
const DB = {
  host: process.env.DB_HOST || '127.0.0.1',
  port: Number(process.env.DB_PORT || 13306),
  user: process.env.DB_USER || 'playersync',
  password: process.env.DB_PASSWORD || 'pleaseChangeThisPassword',
  database: process.env.DB_NAME || 'playersync',
};

// PlayerSync keys player_data by serverPlayer.getUUID().toString(); in offline mode that
// is UUIDUtil.createOfflinePlayerUUID(name) === Java's UUID.nameUUIDFromBytes of
// "OfflinePlayer:<name>" (an MD5 / version-3 UUID). Replicate it so the test can address
// the right row without a live connection.
function offlineUUID(username) {
  const md5 = crypto.createHash('md5').update(`OfflinePlayer:${username}`, 'utf8').digest();
  md5[6] = (md5[6] & 0x0f) | 0x30; // version 3
  md5[8] = (md5[8] & 0x3f) | 0x80; // IETF variant
  const h = md5.toString('hex');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
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
  let lastError = null;
  for (;;) {
    // probe returns a truthy value (handed back to the caller) once satisfied, or any
    // falsy value to keep waiting. A throwing probe counts as "not ready yet" so a single
    // transient failure such as a DB connection blip cannot fail a minutes-long wait.
    // A persistent failure still surfaces through the timeout error below.
    try {
      const value = await probe();
      if (value) {
        return value;
      }
      lastError = null;
    } catch (err) {
      lastError = err;
    }
    if (Date.now() > deadline) {
      const cause = lastError ? ` (last probe error: ${lastError.message || lastError})` : '';
      throw new Error(`Timed out after ${timeoutMs}ms waiting for: ${description}${cause}`);
    }
    await sleep(500);
  }
}

// Direct connection to the mod's database for asserting persisted state. Caller owns the
// lifecycle: `const db = await connectDb(); try { ... } finally { await db.end(); }`.
function connectDb() {
  return mysql.createConnection(DB);
}

// The player_data row for a uuid, or null if the mod has not inserted it yet.
async function queryPlayer(db, uuid) {
  const [rows] = await db.query(
    'SELECT online, last_server, xp FROM player_data WHERE uuid = ?', [uuid]);
  return rows.length ? rows[0] : null;
}

// Poll player_data until `predicate(row)` holds (row is null until the mod inserts it).
async function waitForPlayer(db, uuid, predicate, timeoutMs, description) {
  return waitFor(description, timeoutMs, async () => predicate(await queryPlayer(db, uuid)));
}

function createHarness(tag) {
  const log = (msg) => console.log(`[${tag}] ${new Date().toISOString()} ${msg}`);

  // Resolves with the spawned bot. `opts.timeoutMs` (optional) bounds the wait: if neither
  // spawn nor a pre-spawn failure lands in that window the promise rejects with
  // code JOIN_TIMEOUT and the connection is torn down, so an abandoned login cannot spawn
  // later and linger as a ghost session. Without it the join waits indefinitely.
  function join(server, username, opts = {}) {
    return new Promise((resolve, reject) => {
      log(`Joining ${server.name} (${HOST}:${server.port}) as ${username}`);
      const bot = mineflayer.createBot({
        host: HOST,
        port: server.port,
        username,
        version: VERSION,
        auth: 'offline',
      });
      let settled = false;
      let timer = null;
      // Settles the promise exactly once and returns false if it was already settled.
      const settle = (err, value) => {
        if (settled) return false;
        settled = true;
        if (timer) clearTimeout(timer);
        if (err) reject(err);
        else resolve(value);
        return true;
      };
      // Before the promise settles these mean the join failed: reject so the caller fails
      // fast. Afterwards a late 'end'/'kicked' is the expected result of our own quit() or
      // of the timeout teardown and is ignored, but a late 'error' is logged rather than
      // dropped, because an unhandled 'error' event crashes the process and a silently
      // swallowed one would stall the test until the watchdog fires.
      bot.on('kicked', (reason) => {
        settle(new Error(`Kicked from ${server.name}: ${JSON.stringify(reason)}`));
      });
      bot.on('end', (reason) => {
        settle(new Error(`Connection to ${server.name} ended before spawn (${reason})`));
      });
      bot.on('error', (err) => {
        if (!settle(err)) log(`Post-settle error on ${server.name}: ${err && err.message ? err.message : err}`);
      });
      bot.once('spawn', () => {
        if (settle(null, bot)) {
          log(`Spawned on ${server.name}`);
        } else {
          log(`Late spawn on ${server.name} after the join settled; disconnecting`);
          bot.quit();
        }
      });
      if (opts.timeoutMs) {
        timer = setTimeout(() => {
          const err = new Error(
            `Join to ${server.name} as ${username} neither spawned nor failed within ${opts.timeoutMs}ms`);
          err.code = JOIN_TIMEOUT;
          if (!settle(err)) return;
          // end() only half-closes the connection and arms a 30s destroy timer, so drop the
          // socket outright: the abandoned login must not spawn later or keep node alive.
          bot.end('join timeout');
          if (bot._client && bot._client.socket) bot._client.socket.destroy();
        }, opts.timeoutMs);
      }
    });
  }

  // Confirms the locally derived UUID is the one the server assigned, so a divergence is
  // named here instead of surfacing as an opaque timeout on lookups of a nonexistent row.
  function verifyServerUuid(bot, expectedUuid) {
    const serverUuid = bot._client && bot._client.uuid;
    if (!serverUuid) {
      log(`Server-assigned UUID unavailable; proceeding with derived ${expectedUuid}`);
      return;
    }
    const normalize = (uuid) => String(uuid).replace(/-/g, '').toLowerCase();
    if (normalize(serverUuid) !== normalize(expectedUuid)) {
      throw new Error(`Derived offline UUID ${expectedUuid} != server-assigned ${serverUuid}`);
    }
  }

  async function rcon(server, ...commands) {
    const conn = await Rcon.connect({ host: HOST, port: server.rconPort, password: RCON_PASSWORD });
    try {
      for (const command of commands) {
        const response = await conn.send(command);
        log(`rcon@${server.name} '${command}' -> ${response.trim() || '(no output)'}`);
        if (RCON_ERROR_PATTERNS.some((pattern) => pattern.test(response))) {
          throw new Error(`rcon@${server.name} command failed: '${command}' -> ${response.trim()}`);
        }
      }
    } finally {
      await conn.end();
    }
  }

  // Fail loudly if anything stalls. Without the watchdog a dropped connection can drain
  // the event loop and let node exit 0 without ever reaching an assertion.
  function startWatchdog(timeoutMs) {
    setTimeout(() => {
      console.error(`[${tag}] FAIL: global watchdog timeout (${Math.round(timeoutMs / 60_000)} minutes) — test stalled`);
      process.exit(1);
    }, timeoutMs);
  }

  return { log, join, verifyServerUuid, rcon, startWatchdog };
}

module.exports = {
  HOST,
  VERSION,
  SERVER_A,
  SERVER_B,
  RCON_PASSWORD,
  JOIN_TIMEOUT,
  sleep,
  countItem,
  waitFor,
  offlineUUID,
  connectDb,
  queryPlayer,
  waitForPlayer,
  createHarness,
};
