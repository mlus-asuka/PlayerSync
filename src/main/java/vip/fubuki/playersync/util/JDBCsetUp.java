package vip.fubuki.playersync.util;

import vip.fubuki.playersync.config.JdbcConfig;

import java.sql.*;
import java.util.Properties;


public class JDBCsetUp {

    private static final String url="jdbc:mysql://"+JdbcConfig.HOST.get()+":"+JdbcConfig.PORT.get()+"?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=UTC";
    private static final String username= JdbcConfig.USERNAME.get();
    private static final String password= JdbcConfig.PASSWORD.get();

    public static Connection getConnection() throws InstantiationException, IllegalAccessException, ClassNotFoundException, SQLException {
        Class clazz = Class.forName("com.mysql.cj.jdbc.Driver");
        Driver driver = (Driver) clazz.newInstance();
        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        return driver.connect(url,properties);
    }

    public static ResultSet executeQuery(String sql) throws SQLException, InstantiationException, IllegalAccessException, ClassNotFoundException {
        Statement statement= getConnection().createStatement();
        statement.executeUpdate("USE "+JdbcConfig.DATABASE_NAME.get());
        return statement.executeQuery(sql);
    }

    public static void executeUpdate(String sql) throws SQLException, InstantiationException, IllegalAccessException, ClassNotFoundException {
        Statement statement= getConnection().createStatement();
        statement.executeUpdate("USE "+JdbcConfig.DATABASE_NAME.get());
        statement.executeUpdate(sql);
    }
}
