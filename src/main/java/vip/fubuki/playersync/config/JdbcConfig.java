package vip.fubuki.playersync.config;


import net.minecraftforge.common.ForgeConfigSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;


public class JdbcConfig {
    public static ForgeConfigSpec COMMON_CONFIG;
    public static ForgeConfigSpec.ConfigValue<String> HOST;
    public static ForgeConfigSpec.IntValue PORT;
    public static ForgeConfigSpec.ConfigValue<String> USERNAME;
    public static ForgeConfigSpec.ConfigValue<String> PASSWORD;
    public static ForgeConfigSpec.BooleanValue USE_SSL;


    static {
        ForgeConfigSpec.Builder COMMON_BUILDER = new ForgeConfigSpec.Builder();
        COMMON_BUILDER.comment("General settings").push("general");
        HOST=COMMON_BUILDER.comment("The host of the database").define("host", "localhost");
        PORT = COMMON_BUILDER.comment("database port").defineInRange("db_port", 3306, 0, 65535);
        USE_SSL = COMMON_BUILDER.comment("whether use SSL").define("use_ssl", false);
        USERNAME = COMMON_BUILDER.comment("username").define("user_name", "root");
        PASSWORD = COMMON_BUILDER.comment("password").define("password", "password");
        COMMON_BUILDER.pop();
        COMMON_CONFIG = COMMON_BUILDER.build();
    }
}

