package vip.fubuki.playersync.util;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import vip.fubuki.playersync.config.JdbcConfig;

import java.sql.*;

public class JDBCsetUp {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Returns a connection to the MySQL server.
     * @param selectDatabase if true, the returned URL includes the configured database name.
     * @return a Connection object with the database explicitly selected.
     * @throws SQLException if a database access error occurs.
     */
    public static Connection getConnection(boolean selectDatabase) throws SQLException {
        String dbName = JdbcConfig.DATABASE_NAME.get();
        // Build the base URL
        String url = "jdbc:mysql://" + JdbcConfig.HOST.get() + ":" + JdbcConfig.PORT.get();
        if (selectDatabase && !dbName.isEmpty()) {
            url += "/" + dbName;
        }
        url += "?useUnicode=true&characterEncoding=utf-8&useSSL=" + JdbcConfig.USE_SSL.get()
                + "&serverTimezone=UTC&allowPublicKeyRetrieval=true";
        Connection conn = DriverManager.getConnection(url, JdbcConfig.USERNAME.get(), JdbcConfig.PASSWORD.get());
        // Ensure that the connection uses the desired database by explicitly issuing "USE dbName"
        if (selectDatabase && !dbName.isEmpty()) {
            try (Statement st = conn.createStatement()) {
                st.execute("USE `" + dbName + "`");
            }
        }
        return conn;
    }

    // Default connection always includes the database.
    public static Connection getConnection() throws SQLException {
        return getConnection(true);
    }

    /**
     * Executes a query using a connection that includes the database.
     */
    public static QueryResult executeQuery(String sqlFormatString, Object... args) throws SQLException {
        String sql = String.format(sqlFormatString, args);
        LOGGER.trace(sql);
        Connection connection = getConnection();  // With database selected (and "USE" already run)
        PreparedStatement queryStatement = connection.prepareStatement(sql);
        ResultSet resultSet = queryStatement.executeQuery();
        return new QueryResult(connection, queryStatement, resultSet);
    }

    /**
     * Executes an update using a connection with or without the database within the JDBC URL
     */
    private static void executeUpdate(boolean selectDatabase, String sqlFormatString, Object... args) throws SQLException {
        String sql = String.format(sqlFormatString, args);
        LOGGER.trace(sql);
        try (Connection connection = getConnection()) {  // With database selected
            try (PreparedStatement updateStatement = connection.prepareStatement(sql)) {
                updateStatement.executeUpdate();
            }
        }
    }

    /**
     * Executes an update using a connection that includes the database in the JDBC URL
     */
    public static void executeUpdate(String sqlFormatString, Object... args) throws SQLException {
        executeUpdate(true, sqlFormatString, args);
    }

    /**
     * Executes an update using a connection that does NOT include a default database.
     * This method is used for commands like "CREATE DATABASE IF NOT EXISTS ..."
     */
    public static void executeUpdate(String sql, int dummy) throws SQLException {
        LOGGER.trace(sql);
        try (Connection connection = getConnection(false)) {  // Without default database
            try (PreparedStatement updateStatement = connection.prepareStatement(sql)) {
                updateStatement.executeUpdate();
            }
        }
    }

    /**
     * A helper method for updates with parameters.
     */
    public static void update(String sql, String... argument) throws SQLException {
        LOGGER.trace(sql);
        try (Connection connection = getConnection()) {  // With database selected
            PreparedStatement updateStatement = connection.prepareStatement(sql);
            for (int i = 0; i < argument.length; i++) {
                updateStatement.setString(i + 1, argument[i]);
            }
            updateStatement.executeUpdate();
        }
    }

    /**
     * Executes a parameterized update using PreparedStatement with proper escaping.
     * This prevents SQL injection and data corruption from special characters in values.
     */
    public static void executePreparedUpdate(String sql, Object... params) throws SQLException {
        LOGGER.trace(sql);
        try (Connection connection = getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                stmt.setObject(i + 1, params[i]);
            }
            stmt.executeUpdate();
        }
    }

    /**
     * Executes a parameterized query using PreparedStatement with proper escaping.
     * Caller MUST close the returned QueryResult (use try-with-resources).
     */
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

    public record QueryResult(Connection connection,PreparedStatement preparedStatement, ResultSet resultSet) implements AutoCloseable {
        @Override
        public void close() {
            if (resultSet != null) {
                try {
                    resultSet.close();
                } catch (SQLException e) {
                    LOGGER.error("Error closing ResultSet", e);
                }
            }

            if (preparedStatement != null) {
                try {
                    preparedStatement.close();
                } catch (SQLException e) {
                    LOGGER.error("Error closing PreparedStatement", e);
                }
            }

            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    LOGGER.error("Error closing Connection", e);
                }
            }
        }
    }
}
