package vip.fubuki.playersync.util;

import com.zaxxer.hikari.HikariPoolMXBean;
import vip.fubuki.playersync.PlayerSync;

import java.lang.reflect.Method;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Periodic reporter that logs executor + HikariCP stats every 5 minutes into
 * the PlayerSync sync.log. Lets admins spot queue saturation or pool
 * exhaustion trends without waiting for a crash. Non-invasive — pure read-only.
 *
 * @author vyrriox
 */
public final class PoolStatsReporter {

    private PoolStatsReporter() {}

    private static final long PERIOD_MS = 5 * 60 * 1000L;

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static ScheduledExecutorService scheduler;

    public static void start() {
        if (!RUNNING.compareAndSet(false, true)) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PlayerSync-pool-stats");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        scheduler.scheduleAtFixedRate(PoolStatsReporter::tick, PERIOD_MS, PERIOD_MS, TimeUnit.MILLISECONDS);
        PlayerSync.LOGGER.info("[pool-stats] reporter started (period={}ms)", PERIOD_MS);
    }

    public static void stop() {
        if (!RUNNING.compareAndSet(true, false)) return;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private static void tick() {
        try {
            // Pull executor stats via reflection — VanillaSync.executorService is package-private static
            ThreadPoolExecutor exec = getExecutor();
            int active = exec != null ? exec.getActiveCount() : -1;
            int queue = exec != null ? exec.getQueue().size() : -1;
            int idle = exec != null ? exec.getPoolSize() - exec.getActiveCount() : -1;

            HikariPoolMXBean hikari = JDBCsetUp.getPoolMXBean();
            int hActive = hikari != null ? hikari.getActiveConnections() : -1;
            int hIdle = hikari != null ? hikari.getIdleConnections() : -1;

            SyncLogger.poolStats(active, queue, idle, hActive, hIdle);

            // Warn if queue is getting dangerously full
            if (queue > 400) {
                PlayerSync.LOGGER.warn("[pool-stats] executor queue high: {}/512 — risk of CallerRunsPolicy blocking main thread", queue);
                SyncLogger.warnPlayer("SYSTEM", "Executor queue high: " + queue + "/512");
            }
            if (hActive >= 0 && hActive >= 14) {
                PlayerSync.LOGGER.warn("[pool-stats] HikariCP active connections high: {}/15 — risk of connection starvation", hActive);
                SyncLogger.warnPlayer("SYSTEM", "HikariCP active: " + hActive + "/15");
            }
        } catch (Throwable t) {
            PlayerSync.LOGGER.warn("[pool-stats] tick failed: {}", t.getMessage());
        }
    }

    private static ThreadPoolExecutor getExecutor() {
        try {
            Class<?> c = Class.forName("vip.fubuki.playersync.sync.VanillaSync");
            java.lang.reflect.Field f = c.getDeclaredField("executorService");
            f.setAccessible(true);
            Object o = f.get(null);
            if (o instanceof ThreadPoolExecutor tpe) return tpe;
        } catch (Throwable ignored) {}
        return null;
    }
}
