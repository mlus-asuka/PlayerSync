'use strict';

/*
 * Shared helpers for the e2e bots. `createHarness(tag)` returns connection/RCON
 * helpers bound to a log prefix. The stateless utilities and the server constants
 * are exported directly.
 */

const crypto = require('crypto');
const { execFile } = require('child_process');
const path = require('path');
const mineflayer = require('mineflayer');
const { Rcon } = require('rcon-client');
const mysql = require('mysql2/promise');

const VERSION = '1.20.1';
const HOST = process.env.MC_HOST || '127.0.0.1';
// dbProxy is the toxiproxy proxy carrying *this* server's database traffic.
// There is one per server (see toxiproxy.json), so a toxic slows the named server alone
const SERVER_A = { name: 'server-a', port: 25565, rconPort: 25575, dbProxy: 'mariadb' };
const SERVER_B = { name: 'server-b', port: 25566, rconPort: 25576, dbProxy: 'mariadb-b' };
const RCON_PASSWORD = process.env.RCON_PASSWORD || 'e2e-rcon';
const TOXIPROXY_URL = process.env.TOXIPROXY_URL || 'http://127.0.0.1:8474';

const E2E_DIR = path.resolve(__dirname, '..');
// The suite's own compose file.
const COMPOSE_FILE = path.join(E2E_DIR, 'docker-compose.yml');
// The mod config as the server actually loaded it, inside the container.
const MOD_CONFIG = '/data/config/playersync-common.toml';

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

// The mod stores the inventory as Java's HashMap#toString of {slot=serialized-nbt}, one entry per
// main-inventory slot, each value base64 of the stack's SNBT (use_legacy_serialization is off in
// the e2e config). Summarize it as {itemId: count} so assertions read items, not an opaque blob.
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

// The stored inventory of a player as both the raw column and an {itemId: count} summary.
async function dbInventory(db, uuid) {
  const [rows] = await db.query('SELECT inventory FROM player_data WHERE uuid = ?', [uuid]);
  if (!rows.length) return null;
  // inventory is a mediumblob, so mysql2 hands back a Buffer.
  const blob = rows[0].inventory == null ? null : rows[0].inventory.toString('utf8');
  return { blob, items: summarizeInventory(blob) };
}

// Runs a command to completion and returns its combined output. maxBuffer is generous: a
// server's whole console log comes back through here.
function run(cmd, args, timeoutMs = 60_000) {
  return new Promise((resolve, reject) => {
    execFile(cmd, args, { timeout: timeoutMs, maxBuffer: 256 * 1024 * 1024 }, (err, stdout, stderr) => {
      if (err) {
        reject(new Error(`\`${cmd} ${args.join(' ')}\` failed: ${err.message}\n${stderr}`));
        return;
      }
      resolve(stdout + stderr);
    });
  });
}

// `docker compose ...` against the suite's stack. `overlays` are extra compose files (absolute
// paths, see composeFile) layered on top, which is how a scenario swaps a server's config mount.
function compose(args, { overlays = [], timeoutMs } = {}) {
  const files = [COMPOSE_FILE, ...overlays].flatMap((file) => ['-f', file]);
  return run('docker', ['compose', ...files, ...args], timeoutMs);
}

// Absolute path of a compose file in e2e/, for compose()'s `overlays`.
function composeFile(name) {
  return path.join(E2E_DIR, name);
}

async function containerId(service) {
  const id = (await compose(['ps', '-q', service])).trim();
  if (!id) {
    throw new Error(`No running container for ${service}: is the compose stack up?`);
  }
  return id;
}

// Occurrences of `marker` in a container's console log. Recreating a container resets its log,
// so counts are only ever comparable within one container's lifetime.
async function logCount(id, marker) {
  const logs = await run('docker', ['logs', id], 120_000);
  return logs.split('\n').filter((line) => line.includes(marker)).length;
}

// The name of a container's anonymous /data volume, so a recreate can drop the one it orphans.
async function dataVolume(id) {
  const out = await run('docker', ['inspect', '-f',
    '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Name}}{{end}}{{end}}', id]);
  return out.trim();
}

// The value a server actually loaded for a mod config key, read from the config inside the
// container rather than from the mount source, so a config that never reached /data is caught
// here instead of masquerading as a build that misbehaves.
async function loadedModConfig(id, key) {
  const out = await run('docker', ['exec', id, 'grep', '-E', `^[[:space:]]*${key}`, MOD_CONFIG]);
  const match = out.match(new RegExp(`${key}\\s*=\\s*(\\S+)`));
  if (!match) {
    throw new Error(`Could not read ${key} from ${MOD_CONFIG}: '${out.trim()}'`);
  }
  return match[1];
}

function createHarness(tag) {
  const log = (msg) => console.log(`[${tag}] ${new Date().toISOString()} ${msg}`);

  // Fault injection: a toxiproxy latency toxic on one server's database traffic, or 0 to remove
  // it. `server` is SERVER_A/SERVER_B — each has its own proxy, so the caller has to name the
  // server it means to slow; slowing the other one silently tests nothing. Latency (not a paused
  // DB) because some of the mod's queries run on the server thread, and a frozen DB deadlocks it.
  async function setDbLatency(server, latencyMs) {
    const toxic = `${TOXIPROXY_URL}/proxies/${server.dbProxy}/toxics/db_latency`;
    const remove = async () => {
      const res = await fetch(toxic, { method: 'DELETE' });
      if (!res.ok && res.status !== 404) {
        throw new Error(`Failed to remove ${server.dbProxy} latency toxic: HTTP ${res.status} ${await res.text()}`);
      }
    };
    if (latencyMs <= 0) {
      await remove();
      log(`DB latency toxic removed from ${server.dbProxy} (${server.name})`);
      return;
    }
    await remove(); // clear any toxic left by a previous KEEP=1 run so the POST can't 409
    const res = await fetch(`${TOXIPROXY_URL}/proxies/${server.dbProxy}/toxics`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name: 'db_latency', type: 'latency', stream: 'downstream',
        attributes: { latency: latencyMs, jitter: 0 },
      }),
    });
    if (!res.ok) {
      throw new Error(`Failed to add ${server.dbProxy} latency toxic: HTTP ${res.status} ${await res.text()}`);
    }
    log(`DB latency toxic enabled on ${server.dbProxy} (${server.name}): ${latencyMs}ms per round-trip`);
  }

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
      // Warn when a bot dies: it drops its whole inventory, which can turn a later assertion
      // about stored items into a data-loss failure.
      bot.on('death', () => {
        log(`WARNING: ${username} died on ${server.name}. The inventory has been dropped on the ground`);
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

  // Like rcon(), but hands back the reply and waits longer for it. Used where the reply *is* the
  // assertion (`tag <player> list`), or where the server thread may be mid-query on a
  // latency-toxic'd DB and answer late (`save-all`) — rcon-client's default read timeout is 2s.
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

  return { log, join, verifyServerUuid, rcon, rconAsk, setDbLatency, startWatchdog };
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
  summarizeInventory,
  dbInventory,
  run,
  compose,
  composeFile,
  containerId,
  logCount,
  dataVolume,
  loadedModConfig,
  createHarness,
};
