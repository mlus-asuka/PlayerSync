package vip.fubuki.playersync.util;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import vip.fubuki.playersync.config.JdbcConfig;

import java.sql.*;


public class JDBCsetUp {

    private static HikariDataSource dataSource;

    public static void initDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://"+JdbcConfig.HOST.get()+":"+JdbcConfig.PORT.get()+"?useUnicode=true&characterEncoding=utf-8&useSSL="+JdbcConfig.USE_SSL.get()+"&serverTimezone=UTC&allowPublicKeyRetrieval=true");
        config.setUsername(JdbcConfig.USERNAME.get());
        config.setPassword(JdbcConfig.PASSWORD.get());

        dataSource = new HikariDataSource(config);
    }

    public static Connection getConnection() throws SQLException {
        if (dataSource == null) {
            initDataSource();
        }
        return dataSource.getConnection();
    }

    public static ResultSet executeQuery(String sql) throws SQLException{
        try (Connection connection = getConnection();
             PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
            return preparedStatement.executeQuery();
        }
    }

    public static void executeUpdate(String sql) throws SQLException{
        executeUpdate(sql,false);
    }

    public static void executeUpdate(String sql,boolean init) throws SQLException{
        try (Connection connection = getConnection();
             PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
            if(!init) preparedStatement.executeUpdate("USE "+JdbcConfig.DATABASE_NAME.get());
            preparedStatement.executeUpdate();
        }
    }
}
