package vip.fubuki.playersync.util;

import com.mojang.logging.LogUtils;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import vip.fubuki.playersync.config.JdbcConfig;

import java.sql.*;

/**
 * JDBC utility backed by HikariCP connection pool.
 *
 * Why HikariCP instead of the old manual pool?
 * - Old pool called conn.isValid(2) on every borrow → SELECT 1 round-trip → visible as
 *   "pingInternal" in Spark profiler (~1% server thread constantly).
 * - HikariCP uses TCP keepalive and only validates idle connections at a configurable
 *   interval (keepaliveTime=5min), never on hot-path queries.
 * - Automatic reconnection, proper idle-connection eviction, and thread-safe internals
 *   are all handled by HikariCP without manual LinkedBlockingQueue management.
 */
public class JDBCsetUp {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile HikariDataSource dataSource;

    // -------------------------------------------------------------------------
    // Pool lifecycle
    // -------------------------------------------------------------------------

    /**
     * Initialises the HikariCP pool. Must be called once after the MySQL database
     * has been created (i.e. at the end of the CREATE DATABASE step in PlayerSync).
     * Safe to call again on server-restart scenarios — closes the old pool first.
     */
    public static void initPool() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(buildUrl(true));
        cfg.setUsername(JdbcConfig.USERNAME.get());
        cfg.setPassword(JdbcConfig.PASSWORD.get());

        // AUDIT FIX: hikari_pool_max_size / hikari_leak_threshold_ms were defined,
        // documented and displayed by the status command but never APPLIED — the pool
        // was hardcoded to 15/25000 regardless of what admins configured. Read the
        // config with safe fallbacks (config may not be loaded in edge paths).
        int maxPool = 15;
        long leakMs = 25_000L;
        try {
            maxPool = JdbcConfig.HIKARI_POOL_MAX_SIZE.get();
            leakMs = JdbcConfig.HIKARI_LEAK_THRESHOLD_MS.get();
        } catch (Throwable t) { /* config not loaded — keep safe defaults */ }
        cfg.setMaximumPoolSize(maxPool);
        cfg.setMinimumIdle(Math.min(4, maxPool));

        // Connection lifecycle
        cfg.setConnectionTimeout(10_000L);   // 10 s – fail fast on MySQL outage
        cfg.setIdleTimeout(300_000L);        // 5 min – evict idle connections sooner
        cfg.setMaxLifetime(1_800_000L);      // 30 min – recycle before MySQL wait_timeout
        cfg.setKeepaliveTime(300_000L);      // 5 min – ping idle connections (NOT hot path)

        cfg.setAutoCommit(true);
        cfg.setPoolName("PlayerSync");

        // FIX PERF (C9): 25s default threshold — covers worst-case doPlayerJoin poll
        // bursts without flooding logs with false positives.
        cfg.setLeakDetectionThreshold(leakMs);

        dataSource = new HikariDataSource(cfg);
        LOGGER.info("[PlayerSync] HikariCP pool ready (maxPool={}, minIdle={})",
                cfg.getMaximumPoolSize(), cfg.getMinimumIdle());

        // AUDIT FIX (security): surface the allowPublicKeyRetrieval decision so admins
        // on remote-host + use_ssl=false setups understand a potential auth failure.
        if (!isPublicKeyRetrievalAllowed()) {
            LOGGER.warn("[PlayerSync] use_ssl=false with a remote MySQL host: allowPublicKeyRetrieval is disabled to prevent"
                    + " password disclosure to a man-in-the-middle. If the connection fails with 'Public Key Retrieval is not"
                    + " allowed', set use_ssl=true (recommended) or use a mysql_native_password account.");
        }
    }

    /**
     * Closes all pooled connections. Called on server shutdown.
     */
    public static void shutdownPool() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            dataSource = null;
            LOGGER.info("[PlayerSync] HikariCP pool closed.");
        }
    }

    public static boolean isPoolReady() {
        HikariDataSource ds = dataSource;
        return ds != null && !ds.isClosed();
    }

    /**
     * Exposes the HikariCP MBean for monitoring. Returns {@code null} if the
     * pool is not initialised or already closed. Used by PoolStatsReporter.
     */
    public static com.zaxxer.hikari.HikariPoolMXBean getPoolMXBean() {
        try {
            if (dataSource == null || dataSource.isClosed()) return null;
            return dataSource.getHikariPoolMXBean();
        } catch (Throwable t) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * AUDIT FIX (security — credential disclosure): allowPublicKeyRetrieval=true was
     * appended unconditionally while use_ssl defaults to false. With
     * caching_sha2_password over a non-TLS link, Connector/J RSA-encrypts the password
     * with WHATEVER public key the server presents — a rogue/MITM MySQL endpoint can
     * present its own key and recover the DB password. The flag is now only enabled
     * when the link is TLS-protected (use_ssl=true) or the host is loopback (MITM
     * infeasible) — the default out-of-box config (localhost) keeps working unchanged.
     */
    private static boolean isPublicKeyRetrievalAllowed() {
        try {
            if (JdbcConfig.USE_SSL.get()) return true;
            String host = JdbcConfig.HOST.get();
            if (host == null) return false;
            String h = host.trim().toLowerCase(java.util.Locale.ROOT);
            return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1") || h.equals("[::1]");
        } catch (Throwable t) {
            return false; // config unreadable — err toward the safe side
        }
    }

    private static String buildUrl(boolean selectDatabase) {
        String dbName = JdbcConfig.DATABASE_NAME.get();
        String url = "jdbc:mysql://" + JdbcConfig.HOST.get() + ":" + JdbcConfig.PORT.get();
        if (selectDatabase && !dbName.isEmpty()) {
            url += "/" + dbName;
        }
        // No autoReconnect — HikariCP handles reconnection transparently.
        // FIX PERF: Added MySQL performance parameters:
        // - rewriteBatchedStatements: engages via addBatch()/executeBatch() — see
        //   executeBatchTransaction, which groups identical statements into JDBC batches
        // - cachePrepStmts + useServerPrepStmts: server-side prepared statement cache (15-25% CPU reduction)
        // - prepStmtCacheSize=256: keeps compiled statements in cache across queries
        // - useCompression: compresses network traffic (40-60% reduction for large NBT blobs)
        // - tcpNoDelay: disable Nagle's algorithm for lower latency
        url += "?useUnicode=true&characterEncoding=utf-8&useSSL=" + JdbcConfig.USE_SSL.get()
                + "&serverTimezone=UTC"
                + (isPublicKeyRetrievalAllowed() ? "&allowPublicKeyRetrieval=true" : "")
                + "&rewriteBatchedStatements=true"
                + "&cachePrepStmts=true"
                + "&useServerPrepStmts=true"
                + "&prepStmtCacheSize=256"
                + "&prepStmtCacheSqlLimit=2048"
                + "&useCompression=true"
                + "&tcpNoDelay=true";
        return url;
    }

    /**
     * Returns a connection from the HikariCP pool (selectDatabase=true)
     * or a raw DriverManager connection (selectDatabase=false, used only for
     * startup DDL that must run without a selected database).
     *
     * With HikariCP, calling connection.close() returns the connection to the
     * pool — no separate returnConnection() call needed.
     */
    public static Connection getConnection(boolean selectDatabase) throws SQLException {
        if (!selectDatabase) {
            // Raw connection for DDL that runs before/without the pool database
            return DriverManager.getConnection(
                    buildUrl(false), JdbcConfig.USERNAME.get(), JdbcConfig.PASSWORD.get());
        }
        if (dataSource == null || dataSource.isClosed()) {
            throw new SQLException("[PlayerSync] HikariCP pool is not initialised — call initPool() first.");
        }
        return dataSource.getConnection();
    }

    public static Connection getConnection() throws SQLException {
        return getConnection(true);
    }

    // -------------------------------------------------------------------------
    // Query helpers (API unchanged — callers need no modification)
    // -------------------------------------------------------------------------

    // AUDIT FIX (security — latent SQL injection): the old executeQuery /
    // executeUpdate(String, Object...) helpers interpolated arguments into the SQL
    // text via String.format BEFORE preparing the statement. They had zero
    // data-bearing callers, but sat next to the safe ?-placeholder variants with
    // near-identical signatures — one future misuse away from injection. Removed;
    // DDL goes through the single-arg executeUpdate below, data through the
    // executePrepared* variants.
    public static void executeUpdate(String sql) throws SQLException {
        LOGGER.trace(sql);
        try (Connection conn = getConnection(true);
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.executeUpdate();
            // conn.close() is called by try-with-resources → returned to HikariCP pool
        }
    }

    /** Overload used by startup DDL that must bypass the pool (selectDatabase=false). */
    public static void executeUpdate(String sql, int dummy) throws SQLException {
        LOGGER.trace(sql);
        try (Connection conn = getConnection(false);
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.executeUpdate();
        }
    }

    public static void update(String sql, String... argument) throws SQLException {
        LOGGER.trace(sql);
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < argument.length; i++) {
                stmt.setString(i + 1, argument[i]);
            }
            stmt.executeUpdate();
        }
    }

    public static void executePreparedUpdate(String sql, Object... params) throws SQLException {
        executePreparedUpdateRet(sql, params);
    }

    /**
     * Variant of {@link #executePreparedUpdate(String, Object...)} that returns the
     * number of rows affected. Used by admin commands (clearorphans, peerkill, wipe)
     * to report meaningful counts to the operator.
     */
    public static int executePreparedUpdateRet(String sql, Object... params) throws SQLException {
        LOGGER.trace(sql);
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                stmt.setObject(i + 1, params[i]);
            }
            return stmt.executeUpdate();
        }
    }

    /**
     * FIX PERF: Execute multiple SQL statements in a SINGLE transaction on ONE connection.
     * Previously, writeSnapshotToDB called executePreparedUpdate 4-8 times per player,
     * each opening a new connection from the pool. With 35 players: 140-280 connection
     * borrows + network round-trips. This batches them into 1 connection + 1 commit.
     *
     * Each entry is {sql, params...}. All execute in order within one transaction.
     * If any fails, the entire batch is rolled back.
     *
     * @return array of per-statement affected-row counts (parallel to {@code statements}).
     *         Callers can inspect the first entry to detect silent no-ops caused by
     *         {@code AND last_server=?} guards blocking a stale write.
     */
    public static int[] executeBatchTransaction(Object[]... statements) throws SQLException {
        int[] counts = new int[statements.length];
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                // AUDIT FIX (batching): consecutive entries with IDENTICAL SQL are now
                // grouped into one PreparedStatement via addBatch()/executeBatch() —
                // this is what lets Connector/J's rewriteBatchedStatements collapse
                // N row writes (e.g. saveBackpackSnapshots) into a single multi-row
                // statement. Unique-SQL entries (the core player_data UPDATE whose
                // counts[0] the callers' guard checks depend on) keep the exact
                // per-statement executeUpdate() count as before.
                int idx = 0;
                while (idx < statements.length) {
                    String sql = (String) statements[idx][0];
                    int end = idx + 1;
                    while (end < statements.length && sql.equals(statements[end][0])) end++;
                    LOGGER.trace(sql);
                    if (end - idx == 1) {
                        Object[] entry = statements[idx];
                        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                            for (int i = 1; i < entry.length; i++) {
                                stmt.setObject(i, entry[i]);
                            }
                            counts[idx] = stmt.executeUpdate();
                        }
                    } else {
                        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                            for (int k = idx; k < end; k++) {
                                Object[] entry = statements[k];
                                for (int i = 1; i < entry.length; i++) {
                                    stmt.setObject(i, entry[i]);
                                }
                                stmt.addBatch();
                            }
                            int[] batchCounts = stmt.executeBatch();
                            for (int k = 0; k < batchCounts.length && idx + k < end; k++) {
                                // Rewritten batches may report SUCCESS_NO_INFO (-2) — map to 1
                                // (only grouped entries are affected; callers only inspect
                                // counts[0], which always comes from a unique-SQL entry).
                                counts[idx + k] = batchCounts[k] == java.sql.Statement.SUCCESS_NO_INFO ? 1 : batchCounts[k];
                            }
                        }
                    }
                    idx = end;
                }
                conn.commit();
            } catch (SQLException e) {
                try { conn.rollback(); } catch (SQLException rbEx) {
                    LOGGER.error("[PlayerSync] Rollback failed while handling batch transaction error", rbEx);
                }
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
        return counts;
    }

    public static QueryResult executePreparedQuery(String sql, Object... params) throws SQLException {
        LOGGER.trace(sql);
        Connection conn = getConnection();
        try {
            PreparedStatement stmt = conn.prepareStatement(sql);
            for (int i = 0; i < params.length; i++) {
                stmt.setObject(i + 1, params[i]);
            }
            ResultSet rs = stmt.executeQuery();
            return new QueryResult(conn, stmt, rs);
        } catch (SQLException e) {
            try { conn.close(); } catch (SQLException ignored) {}
            throw e;
        }
    }

    // -------------------------------------------------------------------------
    // QueryResult — holds connection open until caller closes it
    // -------------------------------------------------------------------------

    /**
     * Auto-closeable holder for a live query result.
     * Closing it releases the ResultSet and PreparedStatement, then calls
     * connection.close() which returns the connection to the HikariCP pool.
     */
    public record QueryResult(
            Connection connection,
            PreparedStatement preparedStatement,
            ResultSet resultSet
    ) implements AutoCloseable {

        @Override
        public void close() {
            if (resultSet != null) {
                try { resultSet.close(); } catch (SQLException e) {
                    LOGGER.error("[PlayerSync] Error closing ResultSet", e);
                }
            }
            if (preparedStatement != null) {
                try { preparedStatement.close(); } catch (SQLException e) {
                    LOGGER.error("[PlayerSync] Error closing PreparedStatement", e);
                }
            }
            if (connection != null) {
                try { connection.close(); } catch (SQLException e) {
                    LOGGER.error("[PlayerSync] Error returning connection to pool", e);
                }
            }
        }
    }
}
