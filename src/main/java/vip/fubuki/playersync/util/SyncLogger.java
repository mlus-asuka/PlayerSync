package vip.fubuki.playersync.util;

import vip.fubuki.playersync.config.JdbcConfig;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dedicated file logger for PlayerSync diagnostics.
 * Writes to logs/playersync/sync.log with automatic rotation (max 10MB per file, 5 files kept).
 *
 * Tracks: saves, restores, errors, potential duplications, data loss warnings,
 * cross-server race conditions, and performance metrics.
 *
 * Thread-safe: uses a lock-free queue + async flush to avoid blocking the main thread.
 *
 * @author vyrriox
 */
public class SyncLogger {

    private static final String LOG_DIR = "logs/playersync";
    private static final String LOG_FILE = "sync.log";
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10 MB
    private static final int MAX_FILES = 5;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    // Lock-free queue for async writes (no main thread blocking)
    private static final ConcurrentLinkedQueue<String> writeQueue = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean initialized = new AtomicBoolean(false);
    private static Path logPath;

    // FIX PERF (C3): Dedicated daemon scheduler so log() never opens/closes the file on
    // the caller thread. Previous impl called flushQueue() inline → every log call from
    // the main thread opened a FileWriter, wrote, and closed synchronously.
    private static final ScheduledExecutorService FLUSH_EXEC = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "PlayerSync-logflush");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    // -------------------------------------------------------------------------
    // Initialization
    // -------------------------------------------------------------------------

    public static void init() {
        if (initialized.getAndSet(true)) return;
        try {
            Path dir = Paths.get(LOG_DIR);
            Files.createDirectories(dir);
            logPath = dir.resolve(LOG_FILE);
            rotateIfNeeded();
            writeRaw("=".repeat(80));
            writeRaw("PlayerSync Log — Server ID: " + JdbcConfig.SERVER_ID.get() + " — Started: " + LocalDateTime.now().format(TIME_FMT));
            writeRaw("=".repeat(80));
            // FIX PERF (C3): single background flush every 500ms — no file I/O on hot path.
            FLUSH_EXEC.scheduleWithFixedDelay(SyncLogger::flushQueue, 500, 500, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            System.err.println("[PlayerSync] Failed to initialize SyncLogger: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Public API — categorized log methods
    // -------------------------------------------------------------------------

    /** Normal sync operations (save/restore completed successfully) */
    public static void info(String message, Object... args) {
        log("INFO", message, args);
    }

    /** Warnings that may indicate issues (timeouts, fallbacks, edge cases) */
    public static void warn(String message, Object... args) {
        log("WARN", message, args);
    }

    /** Errors that caused data loss or corruption */
    public static void error(String message, Object... args) {
        log("ERROR", message, args);
    }

    /** Potential duplication detected — highest severity */
    public static void dupeRisk(String playerUuid, String detail) {
        log("DUPE_RISK", "[{}] {}", playerUuid, detail);
    }

    /** Potential data loss detected */
    public static void dataLoss(String playerUuid, String detail) {
        log("DATA_LOSS", "[{}] {}", playerUuid, detail);
    }

    /** Cross-server race condition event */
    public static void raceCondition(String playerUuid, String detail) {
        log("RACE", "[{}] {}", playerUuid, detail);
    }

    /** Performance metric */
    public static void perf(String operation, long durationMs) {
        if (durationMs > 50) { // Only log slow operations (> 50ms)
            log("PERF_SLOW", "{} took {}ms", operation, durationMs);
        }
    }

    /** Player join/leave tracking */
    public static void playerEvent(String playerUuid, String eventType, String detail) {
        log("EVENT", "[{}] {} — {}", playerUuid, eventType, detail);
    }

    // -------------------------------------------------------------------------
    // Save tracking — logs every save with metadata for debugging
    // -------------------------------------------------------------------------

    public static void saveStarted(String playerUuid, String saveType) {
        log("SAVE", "[{}] {} started", playerUuid, saveType);
    }

    public static void saveCompleted(String playerUuid, String saveType, long durationMs) {
        log("SAVE", "[{}] {} completed in {}ms", playerUuid, saveType, durationMs);
    }

    public static void saveFailed(String playerUuid, String saveType, String reason) {
        log("SAVE_FAIL", "[{}] {} FAILED: {}", playerUuid, saveType, reason);
    }

    public static void saveSkipped(String playerUuid, String saveType, String reason) {
        log("SAVE_SKIP", "[{}] {} skipped: {}", playerUuid, saveType, reason);
    }

    /** Logs when a write was blocked by the last_server guard (stale server tried to write) */
    public static void guardBlocked(String playerUuid, int thisServerId, String detail) {
        log("GUARD", "[{}] Write blocked (server={}) — {}", playerUuid, thisServerId, detail);
    }

    // -------------------------------------------------------------------------
    // Restore tracking
    // -------------------------------------------------------------------------

    public static void restoreStarted(String playerUuid) {
        log("RESTORE", "[{}] Data restore started", playerUuid);
    }

    public static void restoreCompleted(String playerUuid, long durationMs) {
        log("RESTORE", "[{}] Data restore completed in {}ms", playerUuid, durationMs);
    }

    public static void restoreFailed(String playerUuid, String reason) {
        log("RESTORE_FAIL", "[{}] Data restore FAILED: {}", playerUuid, reason);
    }

    // -------------------------------------------------------------------------
    // Phase 5: structured diagnostic events
    // -------------------------------------------------------------------------

    /** Force-close of a container on player logout (anti-duplication). */
    public static void containerForceClosed(String playerUuid, String reason) {
        log("CONTAINER_CLOSE", "[{}] {}", playerUuid, reason);
    }

    /** Mod-compat save skipped because capability/handler was unavailable. */
    public static void modCompatSkip(String playerUuid, String modId, String reason) {
        log("MOD_SKIP", "[{}] {} — {}", playerUuid, modId, reason);
    }

    /** Mod-compat save succeeded with metadata (e.g. slot count, NBT keys). */
    public static void modCompatSaved(String playerUuid, String modId, String detail) {
        log("MOD_SAVE", "[{}] {} — {}", playerUuid, modId, detail);
    }

    /** Mod-compat restore succeeded with metadata. */
    public static void modCompatRestored(String playerUuid, String modId, String detail) {
        log("MOD_RESTORE", "[{}] {} — {}", playerUuid, modId, detail);
    }

    /** RS2/backpack/SS storage-level save detail (keyed by storage UUID, not player). */
    public static void storageSave(String storageUuid, String kind, String detail) {
        log("STORAGE", "[{}] {} — {}", storageUuid, kind, detail);
    }

    /** Periodic pool / queue status snapshot (every N minutes). */
    public static void poolStats(int active, int queueSize, int idle, int hikariActive, int hikariIdle) {
        log("POOL", "executor active={} queue={} pool_idle={} | hikari active={} idle={}",
                active, queueSize, idle, hikariActive, hikariIdle);
    }

    /** Generic warning with player context. */
    public static void warnPlayer(String playerUuid, String detail) {
        log("WARN", "[{}] {}", playerUuid, detail);
    }

    /** Detected NBT anomaly (suspicious shape / size). */
    public static void nbtAnomaly(String playerUuid, String detail) {
        log("NBT_ANOMALY", "[{}] {}", playerUuid, detail);
    }

    // -------------------------------------------------------------------------
    // Internal — async file writing
    // -------------------------------------------------------------------------

    private static void log(String level, String message, Object... args) {
        if (!initialized.get()) return;
        try {
            String formatted = formatMessage(message, args);
            String line = String.format("[%s] [%s] [%s] %s",
                    LocalDateTime.now().format(TIME_FMT),
                    Thread.currentThread().getName(),
                    level,
                    formatted);
            writeQueue.add(line);
            // FIX PERF (C3): no inline flush — background scheduler drains the queue.
        } catch (Exception ignored) {}
    }

    private static String formatMessage(String template, Object... args) {
        if (args == null || args.length == 0) return template;
        // Simple {} placeholder replacement (like SLF4J)
        StringBuilder sb = new StringBuilder();
        int argIdx = 0;
        int i = 0;
        while (i < template.length()) {
            if (i < template.length() - 1 && template.charAt(i) == '{' && template.charAt(i + 1) == '}' && argIdx < args.length) {
                sb.append(args[argIdx++]);
                i += 2;
            } else {
                sb.append(template.charAt(i));
                i++;
            }
        }
        return sb.toString();
    }

    // AUDIT FIX (disk DoS): rotation used to run ONLY in init() — a long-running
    // session grew sync.log without bound. The flush thread now re-checks every
    // 20th flush (~every 10s at the 500ms cadence; Files.size() is a cheap stat and
    // the writer is reopened per flush, so rotation never races an open handle).
    private static int flushesSinceRotateCheck = 0;

    private static void flushQueue() {
        if (logPath == null) return;
        if (++flushesSinceRotateCheck >= 20) {
            flushesSinceRotateCheck = 0;
            rotateIfNeeded();
        }
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(logPath.toFile(), true))) {
            String line;
            int count = 0;
            while ((line = writeQueue.poll()) != null && count < 100) {
                writer.write(line);
                writer.newLine();
                count++;
            }
        } catch (IOException ignored) {}
    }

    private static void writeRaw(String line) {
        writeQueue.add(line);
    }

    private static void rotateIfNeeded() {
        if (logPath == null) return;
        // AUDIT FIX: log_rotation_size_mb / log_rotation_max_files were documented
        // config keys but never read — the hardcoded constants now serve only as
        // fallbacks when the config is not loaded yet.
        long maxSize;
        int maxFiles;
        try {
            maxSize = JdbcConfig.LOG_ROTATION_SIZE_MB.get() * 1024L * 1024L;
            maxFiles = JdbcConfig.LOG_ROTATION_MAX_FILES.get();
        } catch (Exception e) {
            maxSize = MAX_FILE_SIZE;
            maxFiles = MAX_FILES;
        }
        try {
            if (Files.exists(logPath) && Files.size(logPath) > maxSize) {
                // Rotate: sync.log → sync.1.log → sync.2.log → ... → delete oldest
                for (int i = maxFiles - 1; i >= 1; i--) {
                    Path src = Paths.get(LOG_DIR, "sync." + i + ".log");
                    Path dst = Paths.get(LOG_DIR, "sync." + (i + 1) + ".log");
                    if (Files.exists(src)) {
                        if (i == maxFiles - 1) {
                            Files.delete(src);
                        } else {
                            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
                Files.move(logPath, Paths.get(LOG_DIR, "sync.1.log"), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {}
    }

    /** Call on server shutdown to flush remaining entries and stop the background writer. */
    public static void shutdown() {
        try { FLUSH_EXEC.shutdown(); } catch (Exception ignored) {}
        try { FLUSH_EXEC.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        flushQueue();
    }
}
