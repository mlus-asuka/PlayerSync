package vip.fubuki.playersync.util;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import vip.fubuki.playersync.config.JdbcConfig;

import java.sql.*;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * JDBC utility with a simple connection pool.
 * Previously, every single query opened a NEW MySQL connection (TCP handshake + auth + USE db),
 * consuming ~10% of server thread time. Now connections are pooled and reused.
 */
public class JDBCsetUp {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Simple connection pool - reuses connections instead of opening new ones every query
    private static final int POOL_SIZE = 5;
    private static final LinkedBlockingQueue<Connection> connectionPool = new LinkedBlockingQueue<>(POOL_SIZE);
    private static String cachedUrl = null;

    private static String buildUrl(boolean selectDatabase) {
        String dbName = JdbcConfig.DATABASE_NAME.get();
        String url = "jdbc:mysql://" + JdbcConfig.HOST.get() + ":" + JdbcConfig.PORT.get();
        if (selectDatabase && !dbName.isEmpty()) {
            url += "/" + dbName;
        }
        url += "?useUnicode=true&characterEncoding=utf-8&useSSL=" + JdbcConfig.USE_SSL.get()
                + "&serverTimezone=UTC&allowPublicKeyRetrieval=true&autoReconnect=true";
        return url;
    }

    /**
     * Gets a connection from the pool, or creates a new one if pool is empty.
     * Connections are validated before returning (checks if still alive).
     */
    public static Connection getConnection(boolean selectDatabase) throws SQLException {
        // For non-default-database connections (startup DDL), always create fresh
        if (!selectDatabase) {
            return DriverManager.getConnection(buildUrl(false), JdbcConfig.USERNAME.get(), JdbcConfig.PASSWORD.get());
        }

        // Try to get a pooled connection
        Connection conn = connectionPool.poll();
        if (conn != null) {
            try {
                if (!conn.isClosed() && conn.isValid(2)) {
                    return conn;
                }
                // Connection is dead, close it and create new
                conn.close();
            } catch (SQLException e) {
                // Connection is broken, ignore and create new
            }
        }

        // Create a new connection
        if (cachedUrl == null) {
            cachedUrl = buildUrl(true);
        }
        conn = DriverManager.getConnection(cachedUrl, JdbcConfig.USERNAME.get(), JdbcConfig.PASSWORD.get());
        String dbName = JdbcConfig.DATABASE_NAME.get();
        if (!dbName.isEmpty()) {
            try (Statement st = conn.createStatement()) {
                st.execute("USE `" + dbName + "`");
            }
        }
        return conn;
    }

    public static Connection getConnection() throws SQLException {
        return getConnection(true);
    }

    /**
     * Returns a connection to the pool instead of closing it.
     * If the pool is full, the connection is closed normally.
     */
    private static void returnConnection(Connection conn) {
        if (conn == null) return;
        try {
            if (conn.isClosed()) return;
            if (!connectionPool.offer(conn)) {
                // Pool is full, close the connection
                conn.close();
            }
        } catch (SQLException e) {
            try { conn.close(); } catch (SQLException ignored) {}
        }
    }

    /**
     * Shuts down the pool, closing all connections.
     */
    public static void shutdownPool() {
        Connection conn;
        while ((conn = connectionPool.poll()) != null) {
            try { conn.close(); } catch (SQLException ignored) {}
        }
    }

    /**
     * Executes a query using a connection that includes the database.
     */
    public static QueryResult executeQuery(String sqlFormatString, Object... args) throws SQLException {
        String sql = String.format(sqlFormatString, args);
        LOGGER.trace(sql);
        Connection connection = getConnection();
        PreparedStatement queryStatement = connection.prepareStatement(sql);
        ResultSet resultSet = queryStatement.executeQuery();
        return new QueryResult(connection, queryStatement, resultSet);
    }

    private static void executeUpdate(boolean selectDatabase, String sqlFormatString, Object... args) throws SQLException {
        String sql = String.format(sqlFormatString, args);
        LOGGER.trace(sql);
        Connection connection = getConnection(selectDatabase);
        try (PreparedStatement updateStatement = connection.prepareStatement(sql)) {
            updateStatement.executeUpdate();
        } finally {
            if (selectDatabase) {
                returnConnection(connection);
            } else {
                connection.close();
            }
        }
    }

    public static void executeUpdate(String sqlFormatString, Object... args) throws SQLException {
        executeUpdate(true, sqlFormatString, args);
    }

    public static void executeUpdate(String sql, int dummy) throws SQLException {
        LOGGER.trace(sql);
        try (Connection connection = getConnection(false);
             PreparedStatement updateStatement = connection.prepareStatement(sql)) {
            updateStatement.executeUpdate();
        }
    }

    public static void update(String sql, String... argument) throws SQLException {
        LOGGER.trace(sql);
        Connection connection = getConnection();
        try (PreparedStatement updateStatement = connection.prepareStatement(sql)) {
            for (int i = 0; i < argument.length; i++) {
                updateStatement.setString(i + 1, argument[i]);
            }
            updateStatement.executeUpdate();
        } finally {
            returnConnection(connection);
        }
    }

    public static void executePreparedUpdate(String sql, Object... params) throws SQLException {
        LOGGER.trace(sql);
        Connection connection = getConnection();
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                stmt.setObject(i + 1, params[i]);
            }
            stmt.executeUpdate();
        } finally {
            returnConnection(connection);
        }
    }

    public static QueryResult executePreparedQuery(String sql, Object... params) throws SQLException {
        LOGGER.trace(sql);
        Connection connection = getConnection();
        PreparedStatement stmt = connection.prepareStatement(sql);
        for (int i = 0; i < params.length; i++) {
            stmt.setObject(i + 1, params[i]);
        }
        ResultSet rs = stmt.executeQuery();
        return new QueryResult(connection, stmt, rs);
    }

    /**
     * QueryResult now returns the connection to the pool on close instead of closing it.
     */
    public record QueryResult(Connection connection, PreparedStatement preparedStatement, ResultSet resultSet) implements AutoCloseable {
        @Override
        public void close() {
            if (resultSet != null) {
                try { resultSet.close(); } catch (SQLException e) { LOGGER.error("Error closing ResultSet", e); }
            }
            if (preparedStatement != null) {
                try { preparedStatement.close(); } catch (SQLException e) { LOGGER.error("Error closing PreparedStatement", e); }
            }
            // Return connection to pool instead of closing
            returnConnection(connection);
        }
    }
}
