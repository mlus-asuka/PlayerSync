package vip.fubuki.playersync.util;

import vip.fubuki.playersync.config.JdbcConfig;

import java.sql.*;


public class JDBCsetUp {

    public static Connection getConnection() throws SQLException {
        String url= "jdbc:mysql://"+JdbcConfig.HOST.get()+":"+JdbcConfig.PORT.get()+"?useUnicode=true&characterEncoding=utf-8&useSSL="+JdbcConfig.USE_SSL.get()+"&serverTimezone=UTC&allowPublicKeyRetrieval=true";
        return DriverManager.getConnection(url, JdbcConfig.USERNAME.get(), JdbcConfig.PASSWORD.get());
    }

    public static QueryResult executeQuery(String sql) throws SQLException{
       Connection connection = getConnection();
        PreparedStatement useStatement = connection.prepareStatement("USE `playersync`");
        useStatement.executeUpdate();

        PreparedStatement queryStatement = connection.prepareStatement(sql);
        ResultSet resultSet = queryStatement.executeQuery();
       return new QueryResult(connection,resultSet);
    }

    public static int executeUpdate(String sql) throws SQLException{
        try (Connection connection = getConnection()) {

            PreparedStatement useStatement = connection.prepareStatement("USE `playersync`");
            useStatement.executeUpdate();

            try (PreparedStatement updateStatement = connection.prepareStatement(sql)) {
                return updateStatement.executeUpdate();
            }
        }
    }

    public static int executeUpdate(String sql,int i) throws SQLException{
        try (Connection connection = getConnection()) {

            try (PreparedStatement updateStatement = connection.prepareStatement(sql)) {
                return updateStatement.executeUpdate();
            }
        }
    }

    public static class QueryResult{
        private final Connection connection;
        private final ResultSet resultSet;

        public QueryResult(Connection connection, ResultSet resultSet) {
            this.connection = connection;
            this.resultSet = resultSet;
        }

        public Connection getConnection() {
            return connection;
        }

        public ResultSet getResultSet() {
            return resultSet;
        }
    }
}
