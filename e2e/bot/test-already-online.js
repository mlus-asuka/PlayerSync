'use strict';

/*
 * Positive test for the already-online kick (kick_when_already_online = true).
 *
 * A player online on one sync server must not be able to log into another at the same
 * time: PlayerSync's negotiation handler sees online=1 with a fresh heartbeat on the other
 * server and disconnects the second login with playersync.already_online. This asserts the
 * behavioural contrast directly — server B refuses the join while the bot is online on
 * server A, then accepts it once the bot has left — and requires the refusal itself to be
 * attributable to the gate, so an unrelated login failure can't be mistaken for it.
 *
 * Exit code 0 = pass, 1 = fail.
 */

const {
  SERVER_A, SERVER_B, JOIN_TIMEOUT,
  offlineUUID, connectDb, waitForPlayer, createHarness,
} = require('./lib');

const { log, join, verifyServerUuid, startWatchdog } = createHarness('e2e-online');

const BOT_NAME = 'OnlineTester';
const BOT_UUID = offlineUUID(BOT_NAME);

startWatchdog(5 * 60_000);

// Resolves with the refusal when the join is rejected before spawn, and fails when the
// join spawns or nothing happens within the timeout. The bounded join() owns the timeout,
// so a login that never resolves is disconnected rather than left in flight to spawn
// afterwards.
async function expectRejectedJoin(server, username, timeoutMs) {
  let bot;
  try {
    bot = await join(server, username, { timeoutMs });
  } catch (err) {
    if (err && err.code === JOIN_TIMEOUT) {
      throw new Error(`${server.name} neither accepted nor refused the login within ${timeoutMs}ms`);
    }
    return err;
  }
  bot.quit();
  throw new Error(`${server.name} accepted a login that should have been refused (player online elsewhere)`);
}

async function main() {
  const db = await connectDb();
  try {
    // --- Phase 1: bot is online on server A ---
    const botA = await join(SERVER_A, BOT_NAME);
    verifyServerUuid(botA, BOT_UUID);
    await waitForPlayer(db, BOT_UUID, (p) => p && p.online === 1, 30_000,
      'player registered online on server A');
    log('Bot online on server A');

    // --- Phase 2: a second login on server B must be refused ---
    const rejection = await expectRejectedJoin(SERVER_B, BOT_NAME, 30_000);
    const reason = String((rejection && rejection.message) || rejection);
    log(`Server B refused the second login while online on A: ${reason}`);
    // The refusal must be attributable to the already-online gate: either the explicit kick
    // ("playersync.already_online" or its "synchronization server" fallback) or a
    // reason-less pre-spawn close. The gate's check runs on a mod worker thread, and a
    // refusal landing during the login phase closes the connection without a disconnect
    // packet, so whether the reason reaches the client depends on timing and both outcomes
    // are legitimate refusals. Any other stated reason, such as a whitelist or ban kick,
    // fails the test.
    const alreadyOnlineKick = /already_online|synchronization server/i.test(reason);
    const silentLoginClose = /ended before spawn \(socketClosed\)/.test(reason);
    if (!alreadyOnlineKick && !silentLoginClose) {
      throw new Error(`server B refused the second login, but not recognizably for being already online: ${reason}`);
    }

    // --- Phase 3: once the player leaves A, server B accepts the login ---
    // Confirms the refusal was caused by the online state, not a misconfiguration.
    botA.quit();
    await waitForPlayer(db, BOT_UUID, (p) => p && p.online === 0, 30_000,
      'player offline after leaving server A');
    const botB = await join(SERVER_B, BOT_NAME);
    log('Server B accepted the login once the player was no longer online on A');
    botB.quit();
    // Confirm the logout persisted (online=0) rather than sleeping a guessed duration.
    await waitForPlayer(db, BOT_UUID, (p) => p && p.online === 0, 30_000,
      'player offline after leaving server B');
  } finally {
    await db.end();
  }

  log('PASS: a player online on one server is kept off the other, and let in once offline');
  process.exit(0);
}

main().catch((err) => {
  console.error(`[e2e-online] FAIL: ${err.stack || err}`);
  process.exit(1);
});
