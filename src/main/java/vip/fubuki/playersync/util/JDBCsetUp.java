package vip.fubuki.playersync.util;

import vip.fubuki.playersync.config.JdbcConfig;

import java.sql.*;
import java.util.Properties;



public class JDBCsetUp {


    @SuppressWarnings("deprecation")
    public static Connection getConnection(boolean init) throws InstantiationException, IllegalAccessException, ClassNotFoundException, SQLException {
        Class<?> clazz = Class.forName("com.mysql.cj.jdbc.Driver");
        Driver driver = (Driver) clazz.newInstance();
        Properties properties = new Properties();
        properties.setProperty("user",JdbcConfig.USERNAME.get());
        properties.setProperty("password",JdbcConfig.PASSWORD.get());
        String url;
        if(init) {
            url="jdbc:mysql://"+JdbcConfig.HOST.get()+":"+JdbcConfig.PORT.get()+"?useUnicode=true&characterEncoding=utf-8&useSSL="+JdbcConfig.USE_SSL.get()+"&serverTimezone=UTC&allowPublicKeyRetrieval=true";
        }else{
            url="jdbc:mysql://"+JdbcConfig.HOST.get()+":"+JdbcConfig.PORT.get()+"/"+JdbcConfig.DATABASE_NAME.get()+"?useUnicode=true&characterEncoding=utf-8&useSSL="+JdbcConfig.USE_SSL.get()+"&serverTimezone=UTC&allowPublicKeyRetrieval=true";
        }
        return driver.connect(url,properties);
    }

    public static ResultSet executeQuery(String sql) throws SQLException, InstantiationException, IllegalAccessException, ClassNotFoundException {
        Statement statement= getConnection(false).createStatement();
        return statement.executeQuery(sql);
    }

    public static void executeUpdate(String sql) throws SQLException, InstantiationException, IllegalAccessException, ClassNotFoundException {
        executeUpdate(sql,false);
    }

    public static void executeUpdate(String sql,boolean init) throws SQLException, InstantiationException, IllegalAccessException, ClassNotFoundException {
        Statement statement= getConnection(init).createStatement();
        if(!init) statement.executeUpdate("USE "+JdbcConfig.DATABASE_NAME.get());
        statement.executeUpdate(sql);
        statement.close();
    }
}
