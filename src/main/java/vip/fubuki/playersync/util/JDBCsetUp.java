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

        // FIX PERF (C9): right-sized pool. 25 was oversized; empirical HikariCP rule is
        // ~ cores*2 + spindles. 15 handles 35 concurrent players comfortably and reduces
        // MySQL server-side context switching.
        cfg.setMaximumPoolSize(15);
        cfg.setMinimumIdle(4);

        // Connection lifecycle
        cfg.setConnectionTimeout(10_000L);   // 10 s – fail fast on MySQL outage
        cfg.setIdleTimeout(300_000L);        // 5 min – evict idle connections sooner
        cfg.setMaxLifetime(1_800_000L);      // 30 min – recycle before MySQL wait_timeout
        cfg.setKeepaliveTime(300_000L);      // 5 min – ping idle connections (NOT hot path)

        cfg.setAutoCommit(true);
        cfg.setPoolName("PlayerSync");

        // FIX PERF (C9): 25s threshold — covers worst-case doPlayerJoin poll bursts without
        // flooding logs with false positives. Previous 10s fired during legitimate 15-30s polls.
        cfg.setLeakDetectionThreshold(25_000L);

        dataSource = new HikariDataSource(cfg);
        LOGGER.info("[PlayerSync] HikariCP pool ready (maxPool={}, minIdle={})",
                cfg.getMaximumPoolSize(), cfg.getMinimumIdle());
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

    private static String buildUrl(boolean selectDatabase) {
        String dbName = JdbcConfig.DATABASE_NAME.get();
        String url = "jdbc:mysql://" + JdbcConfig.HOST.get() + ":" + JdbcConfig.PORT.get();
        if (selectDatabase && !dbName.isEmpty()) {
            url += "/" + dbName;
        }
        // No autoReconnect — HikariCP handles reconnection transparently.
        // FIX PERF: Added MySQL performance parameters:
        // - rewriteBatchedStatements: rewrites batch INSERTs into multi-row (5-30x faster)
        // - cachePrepStmts + useServerPrepStmts: server-side prepared statement cache (15-25% CPU reduction)
        // - prepStmtCacheSize=256: keeps compiled statements in cache across queries
        // - useCompression: compresses network traffic (40-60% reduction for large NBT blobs)
        // - tcpNoDelay: disable Nagle's algorithm for lower latency
        url += "?useUnicode=true&characterEncoding=utf-8&useSSL=" + JdbcConfig.USE_SSL.get()
                + "&serverTimezone=UTC&allowPublicKeyRetrieval=true"
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

    public static QueryResult executeQuery(String sqlFormatString, Object... args) throws SQLException {
        String sql = String.format(sqlFormatString, args);
        LOGGER.trace(sql);
        Connection connection = getConnection();
        try {
            PreparedStatement stmt = connection.prepareStatement(sql);
            ResultSet rs = stmt.executeQuery();
            return new QueryResult(connection, stmt, rs);
        } catch (SQLException e) {
            try { connection.close(); } catch (SQLException ignored) {}
            throw e;
        }
    }

    private static void executeUpdateInternal(boolean selectDatabase, String sqlFormatString, Object... args) throws SQLException {
        String sql = String.format(sqlFormatString, args);
        LOGGER.trace(sql);
        try (Connection conn = getConnection(selectDatabase);
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.executeUpdate();
            // conn.close() is called by try-with-resources:
            //   - pool connection → returned to HikariCP pool
            //   - raw connection  → truly closed
        }
    }

    public static void executeUpdate(String sqlFormatString, Object... args) throws SQLException {
        executeUpdateInternal(true, sqlFormatString, args);
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
        LOGGER.trace(sql);
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                stmt.setObject(i + 1, params[i]);
            }
            stmt.executeUpdate();
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
                for (int idx = 0; idx < statements.length; idx++) {
                    Object[] entry = statements[idx];
                    String sql = (String) entry[0];
                    LOGGER.trace(sql);
                    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                        for (int i = 1; i < entry.length; i++) {
                            stmt.setObject(i, entry[i]);
                        }
                        counts[idx] = stmt.executeUpdate();
                    }
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
