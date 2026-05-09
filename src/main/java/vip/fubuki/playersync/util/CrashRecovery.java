package vip.fubuki.playersync.util;

import vip.fubuki.playersync.PlayerSync;
import vip.fubuki.playersync.config.JdbcConfig;

import java.sql.ResultSet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Crash-recovery + shutdown-hook helper.
 *
 * <p>Installs a JVM shutdown hook that flushes pending saves and writes a
 * graceful-shutdown marker into {@code server_info}. On next startup, scans
 * {@code player_data} for rows stuck at {@code online=1} on this server and
 * clears them — covers {@code kill -9} / OOM / JVM abort scenarios where the
 * normal ServerStoppingEvent path never ran.
 *
 * <p>Companion of {@link HeartbeatService} which keeps {@code server_info.last_update}
 * fresh so peer servers can detect this one as alive.
 *
 * @author vyrriox
 */
public final class CrashRecovery {

    private CrashRecovery() {}

    private static final AtomicBoolean HOOK_INSTALLED = new AtomicBoolean(false);
    private static volatile Runnable flushCallback;

    /**
     * Registers a JVM shutdown hook. Called once from PlayerSync.onServerStarting
     * AFTER the DB pool is up. The {@code flushTask} is invoked on JVM shutdown —
     * use it to snapshot all still-online players synchronously (no async executor,
     * the pool may already be draining).
     */
    public static void installShutdownHook(Runnable flushTask) {
        if (!HOOK_INSTALLED.compareAndSet(false, true)) return;
        flushCallback = flushTask;

        Thread hook = new Thread(() -> {
            try {
                PlayerSync.LOGGER.warn("[crash-recovery] JVM shutdown hook fired — flushing pending saves");
                SyncLogger.playerEvent("SYSTEM", "JVM_SHUTDOWN_HOOK", "Flushing pending saves before JVM exit");
                if (flushCallback != null) {
                    try {
                        flushCallback.run();
                    } catch (Throwable t) {
                        PlayerSync.LOGGER.error("[crash-recovery] flush callback threw", t);
                    }
                }
                // Mark this server as gracefully stopped so peers know it's dead.
                try {
                    JDBCsetUp.executePreparedUpdate(
                            "UPDATE " + Tables.serverInfo() + " SET enable=0, last_update=? WHERE id=?",
                            System.currentTimeMillis(), JdbcConfig.SERVER_ID.get());
                } catch (Exception e) {
                    PlayerSync.LOGGER.warn("[crash-recovery] could not mark server stopped: {}", e.getMessage());
                }
            } catch (Throwable t) {
                // NEVER let the hook throw — it would block JVM exit.
                PlayerSync.LOGGER.error("[crash-recovery] hook failed", t);
            }
        }, "PlayerSync-shutdown-hook");
        hook.setDaemon(false); // MUST be non-daemon: daemon threads are killed on exit
        Runtime.getRuntime().addShutdownHook(hook);
        PlayerSync.LOGGER.info("[crash-recovery] JVM shutdown hook installed");
    }

    /**
     * Scans {@code player_data} for orphaned online=1 rows on this server and
     * clears them. Called from PlayerSync.onServerStarting AFTER the tables are
     * created. This is the recovery path for players who were online when the
     * server was killed ungracefully (kill -9, OOM, host reboot).
     */
    public static void clearOrphanedOnlineFlags() {
        int serverId = JdbcConfig.SERVER_ID.get();
        try {
            // Count first so we know what we're about to clear.
            int count = 0;
            try (JDBCsetUp.QueryResult qr = JDBCsetUp.executePreparedQuery(
                    "SELECT COUNT(*) AS c FROM " + Tables.playerData() + " WHERE last_server=? AND online=1",
                    serverId)) {
                ResultSet rs = qr.resultSet();
                if (rs.next()) count = rs.getInt("c");
            }
            JDBCsetUp.executePreparedUpdate(
                    "UPDATE " + Tables.playerData() + " SET online=0 WHERE last_server=? AND online=1",
                    serverId);
            if (count > 0) {
                PlayerSync.LOGGER.warn("[crash-recovery] cleared {} orphan online=1 rows from previous session (server_id={})",
                        count, serverId);
                SyncLogger.playerEvent("SYSTEM", "ORPHAN_CLEAR",
                        "Cleared " + count + " online=1 rows left by previous session crash");
            } else {
                PlayerSync.LOGGER.info("[crash-recovery] no orphan online=1 rows found — previous shutdown was clean");
            }
        } catch (Exception e) {
            PlayerSync.LOGGER.error("[crash-recovery] failed to scan for orphans", e);
        }
    }

    /**
     * Reports peer servers whose heartbeat is stale. Informational — useful to
     * surface zombie server_ids that could trip doPlayerJoin's poll. Called once
     * on startup.
     */
    public static void reportZombiePeers(long staleAfterMs) {
        try (JDBCsetUp.QueryResult qr = JDBCsetUp.executePreparedQuery(
                "SELECT id, last_update FROM " + Tables.serverInfo() + " WHERE enable=1 AND id<>?",
                JdbcConfig.SERVER_ID.get())) {
            ResultSet rs = qr.resultSet();
            long now = System.currentTimeMillis();
            int zombies = 0;
            while (rs.next()) {
                int id = rs.getInt("id");
                long last = rs.getLong("last_update");
                long age = now - last;
                if (id == 0 || age > staleAfterMs) {
                    zombies++;
                    PlayerSync.LOGGER.warn("[crash-recovery] peer server_id={} is zombie (last_update age={}ms, enabled=true)",
                            id, age);
                }
            }
            if (zombies > 0) {
                SyncLogger.playerEvent("SYSTEM", "ZOMBIE_PEERS", zombies + " peer server(s) appear stale");
            }
        } catch (Exception e) {
            PlayerSync.LOGGER.warn("[crash-recovery] zombie peer scan failed: {}", e.getMessage());
        }
    }
}
