package vip.fubuki.playersync.util;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import vip.fubuki.playersync.PlayerSync;
import vip.fubuki.playersync.config.JdbcConfig;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Independent scheduler that triggers a full periodic flush for every online
 * player at {@code auto_save_interval_minutes} intervals.
 *
 * <p>This is decoupled from NeoForge's {@code PlayerEvent.SaveToFile} so the
 * cadence is predictable and configurable — NeoForge's event fires on the
 * vanilla autosave tick, which an admin may have tuned elsewhere. We delegate
 * the actual save work to the main thread via {@code server.execute(...)} so
 * snapshots run on the safe thread, then the save itself hops to the BG pool
 * via the usual {@code PlayerEvent.SaveToFile} path.
 *
 * @author vyrriox
 */
public final class PeriodicSaveService {

    private PeriodicSaveService() {}

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static ScheduledExecutorService scheduler;

    public static void start() {
        int minutes = JdbcConfig.AUTO_SAVE_INTERVAL_MINUTES.get();
        if (minutes <= 0) {
            PlayerSync.LOGGER.info("[periodic-save] disabled (auto_save_interval_minutes=0)");
            return;
        }
        if (!RUNNING.compareAndSet(false, true)) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PlayerSync-periodic-save");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        long periodMs = minutes * 60_000L;
        // First tick after one full period, not immediately — gives the server
        // time to finish startup before we start scheduling DB work.
        scheduler.scheduleAtFixedRate(PeriodicSaveService::tick, periodMs, periodMs, TimeUnit.MILLISECONDS);
        PlayerSync.LOGGER.info("[periodic-save] started (interval={}min)", minutes);
    }

    public static void stop() {
        if (!RUNNING.compareAndSet(true, false)) return;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        PlayerSync.LOGGER.info("[periodic-save] stopped");
    }

    private static void tick() {
        try {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null || !server.isRunning()) return;
            // Hop to main thread — snapshots must happen on server thread.
            // PHASE 7 PERF: skip the whole tick if no one is online — no need to
            // hop to main thread or log anything for an empty server.
            if (server.getPlayerList().getPlayers().isEmpty()) return;
            server.execute(() -> {
                try {
                    int online = 0;
                    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                        if (player.getTags().contains("player_synced") && !player.isDeadOrDying()) {
                            // Reuse VanillaSync's SaveToFile-style snapshot + async-write machinery.
                            // We emit a synthetic SaveToFile event by calling the public entry point.
                            vip.fubuki.playersync.sync.VanillaSync.snapshotAndQueueSave(player, "PERIODIC");
                            online++;
                        }
                    }
                    if (online > 0) {
                        PlayerSync.LOGGER.info("[periodic-save] queued snapshots for {} player(s)", online);
                        SyncLogger.playerEvent("SYSTEM", "PERIODIC_TICK",
                                "Queued " + online + " player snapshot(s)");
                    }
                } catch (Throwable t) {
                    PlayerSync.LOGGER.error("[periodic-save] tick body failed", t);
                }
            });
        } catch (Throwable t) {
            PlayerSync.LOGGER.warn("[periodic-save] scheduling tick failed: {}", t.getMessage());
        }
    }
}
