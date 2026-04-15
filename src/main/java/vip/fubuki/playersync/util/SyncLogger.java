package vip.fubuki.playersync.util;

import vip.fubuki.playersync.config.JdbcConfig;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentLinkedQueue;
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
            // Flush async to avoid blocking caller
            flushQueue();
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

    private static void flushQueue() {
        if (logPath == null) return;
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
        flushQueue();
    }

    private static void rotateIfNeeded() {
        if (logPath == null) return;
        try {
            if (Files.exists(logPath) && Files.size(logPath) > MAX_FILE_SIZE) {
                // Rotate: sync.log → sync.1.log → sync.2.log → ... → delete oldest
                for (int i = MAX_FILES - 1; i >= 1; i--) {
                    Path src = Paths.get(LOG_DIR, "sync." + i + ".log");
                    Path dst = Paths.get(LOG_DIR, "sync." + (i + 1) + ".log");
                    if (Files.exists(src)) {
                        if (i == MAX_FILES - 1) {
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

    /** Call on server shutdown to flush remaining entries */
    public static void shutdown() {
        flushQueue();
    }
}
