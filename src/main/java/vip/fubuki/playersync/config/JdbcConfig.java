package vip.fubuki.playersync.config;


import net.minecraftforge.common.ForgeConfigSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


public class JdbcConfig {
    public static ForgeConfigSpec COMMON_CONFIG;
    public static ForgeConfigSpec.ConfigValue<String> HOST;
    public static ForgeConfigSpec.ConfigValue<String> DATABASE_NAME;
    public static ForgeConfigSpec.IntValue PORT;
    public static ForgeConfigSpec.ConfigValue<String> USERNAME;
    public static ForgeConfigSpec.ConfigValue<String> PASSWORD;
    public static ForgeConfigSpec.ConfigValue<List<String>> SYNC_WORLD;
    public static ForgeConfigSpec.BooleanValue USE_SSL;


    static {
        ForgeConfigSpec.Builder COMMON_BUILDER = new ForgeConfigSpec.Builder();
        COMMON_BUILDER.comment("General settings").push("general");
        HOST=COMMON_BUILDER.comment("The host of the database").define("host", "localhost");
        DATABASE_NAME= COMMON_BUILDER.comment("Database name").define("database_name", "playersync");
        PORT = COMMON_BUILDER.comment("database port").defineInRange("db_port", 3306, 0, 65535);
        USE_SSL = COMMON_BUILDER.comment("whether use SSL").define("use_ssl", false);
        USERNAME = COMMON_BUILDER.comment("username").define("user_name", "root");
        PASSWORD = COMMON_BUILDER.comment("password").define("password", "password");
        SYNC_WORLD = COMMON_BUILDER.comment("The worlds that will be synchronized.If running in server it is supposed to have only one").define("sync_world", new ArrayList<String>());
        COMMON_BUILDER.pop();
        COMMON_CONFIG = COMMON_BUILDER.build();
    }
}

