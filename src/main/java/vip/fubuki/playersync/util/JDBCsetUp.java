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

        // FIX PERF: Increased pool for 35+ player servers.
        // Old: 10 max / 2 idle → 35 concurrent saves queued on 10 connections → 250ms+ wait.
        // New: 25 max / 4 idle → handles peak load without connection starvation.
        cfg.setMaximumPoolSize(25);
        cfg.setMinimumIdle(4);

        // Connection lifecycle
        cfg.setConnectionTimeout(30_000L);   // 30 s – how long to wait for a free slot
        cfg.setIdleTimeout(600_000L);        // 10 min – evict idle connections
        cfg.setMaxLifetime(1_800_000L);      // 30 min – recycle before MySQL wait_timeout
        cfg.setKeepaliveTime(300_000L);      // 5 min – ping idle connections (NOT hot path)

        cfg.setAutoCommit(true);
        cfg.setPoolName("PlayerSync");

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

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static String buildUrl(boolean selectDatabase) {
        String dbName = JdbcConfig.DATABASE_NAME.get();
        String url = "jdbc:mysql://" + JdbcConfig.HOST.get() + ":" + JdbcConfig.PORT.get();
        if (selectDatabase && !dbName.isEmpty()) {
            url += "/" + dbName;
        }
        // No autoReconnect — HikariCP handles reconnection transparently
        url += "?useUnicode=true&characterEncoding=utf-8&useSSL=" + JdbcConfig.USE_SSL.get()
                + "&serverTimezone=UTC&allowPublicKeyRetrieval=true";
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
