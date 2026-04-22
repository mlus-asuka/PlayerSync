package vip.fubuki.playersync.util;

import vip.fubuki.playersync.PlayerSync;
import vip.fubuki.playersync.config.JdbcConfig;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Periodic {@code server_info.last_update} heartbeat.
 *
 * <p>Runs on a dedicated single-threaded scheduler at a fixed interval so peer
 * servers can detect this server as alive via {@code isPeerServerStale()} in
 * {@code VanillaSync.doPlayerJoin}. Without this, a server that stops issuing
 * updates (e.g. hung main thread) would be treated as alive indefinitely by
 * rejoining players on other servers, causing the 30s poll timeouts seen in
 * production logs.
 *
 * @author vyrriox
 */
public final class HeartbeatService {

    private HeartbeatService() {}

    /**
     * Heartbeat period: configurable via {@code heartbeat_interval_seconds}.
     * Paired with {@code peer_stale_threshold_seconds}.
     */
    private static long currentPeriodMs() {
        try {
            return JdbcConfig.HEARTBEAT_INTERVAL_SECONDS.get() * 1000L;
        } catch (Throwable t) {
            return 30_000L;
        }
    }

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static ScheduledExecutorService scheduler;

    public static void start() {
        if (!RUNNING.compareAndSet(false, true)) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PlayerSync-heartbeat");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        long period = currentPeriodMs();
        scheduler.scheduleAtFixedRate(HeartbeatService::tick, period, period, TimeUnit.MILLISECONDS);
        PlayerSync.LOGGER.info("[heartbeat] started (period={}ms, server_id={})", period, JdbcConfig.SERVER_ID.get());
    }

    public static void stop() {
        if (!RUNNING.compareAndSet(true, false)) return;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        PlayerSync.LOGGER.info("[heartbeat] stopped");
    }

    private static void tick() {
        try {
            int serverId = JdbcConfig.SERVER_ID.get();
            JDBCsetUp.executePreparedUpdate(
                    "UPDATE " + Tables.serverInfo() + " SET last_update=?, enable=1 WHERE id=?",
                    System.currentTimeMillis(), serverId);
        } catch (Throwable t) {
            // Do not kill the scheduler on a transient DB error — log and retry next tick.
            PlayerSync.LOGGER.warn("[heartbeat] tick failed: {}", t.getMessage());
        }
    }
}
