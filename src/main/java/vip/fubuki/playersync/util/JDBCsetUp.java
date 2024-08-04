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
        PreparedStatement useStatement = connection.prepareStatement("USE ?");
        useStatement.setString(1, JdbcConfig.DATABASE_NAME.get());
        useStatement.executeUpdate();

        PreparedStatement queryStatement = connection.prepareStatement(sql);
        ResultSet resultSet = queryStatement.executeQuery();
       return new QueryResult(connection,resultSet);
    }

    public static void executeUpdate(String sql) throws SQLException{
        try (Connection connection = getConnection()) {

            PreparedStatement useStatement = connection.prepareStatement("USE ?");
            useStatement.setString(1, JdbcConfig.DATABASE_NAME.get());
            useStatement.executeUpdate();

            try (PreparedStatement updateStatement = connection.prepareStatement(sql)) {
                updateStatement.executeUpdate();
            }
        }
    }

    public static void Update(String sql, String... argument) throws SQLException{
       Connection connection = getConnection();

       PreparedStatement useStatement = connection.prepareStatement("USE ?");
       useStatement.setString(1, JdbcConfig.DATABASE_NAME.get());
       useStatement.executeUpdate();

       PreparedStatement updateStatement = connection.prepareStatement(sql);
       for (int i = 1; i <= argument.length; i++) {
           updateStatement.setString(i,argument[i]);
       }
       updateStatement.executeUpdate();
    }

    public static void executeUpdate(String sql, int i) throws SQLException{
        try (Connection connection = getConnection()) {

            try (PreparedStatement updateStatement = connection.prepareStatement(sql)) {
                updateStatement.executeUpdate();
            }
        }
    }

    public record QueryResult(Connection connection, ResultSet resultSet) {
    }
}
