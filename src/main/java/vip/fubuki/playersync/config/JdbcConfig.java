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
    public static ForgeConfigSpec.ConfigValue<String> DATABASE_NAME;
    public static ForgeConfigSpec.ConfigValue<List<String>> SYNC_WORLD;
    public static ForgeConfigSpec.BooleanValue USE_SSL;
    public static ForgeConfigSpec.BooleanValue SYNC_CHAT;
    public static ForgeConfigSpec.BooleanValue IS_CHAT_SERVER;
    public static ForgeConfigSpec.ConfigValue<String> CHAT_SERVER_IP;
    public static ForgeConfigSpec.IntValue CHAT_SERVER_PORT;

    public static ForgeConfigSpec.ConfigValue<Integer> SERVER_ID;


    static {
        ForgeConfigSpec.Builder COMMON_BUILDER = new ForgeConfigSpec.Builder();
        COMMON_BUILDER.comment("General settings").push("general");
        HOST=COMMON_BUILDER.comment("The host of the database").define("host", "localhost");
        PORT = COMMON_BUILDER.comment("database port").defineInRange("db_port", 3306, 0, 65535);
        USE_SSL = COMMON_BUILDER.comment("whether use SSL").define("use_ssl", false);
        USERNAME = COMMON_BUILDER.comment("username").define("user_name", "root");
        PASSWORD = COMMON_BUILDER.comment("password").define("password", "password");
        DATABASE_NAME = COMMON_BUILDER.comment("database name").define("db_name","playersync");
        SERVER_ID = COMMON_BUILDER.comment("the server id should be unique").define("Server_id", new Random().nextInt(1,Integer.MAX_VALUE-1));
        SYNC_WORLD = COMMON_BUILDER.comment("The worlds that will be synchronized.If running in server it is supposed to have only one").define("sync_world", new ArrayList<>());
        SYNC_CHAT= COMMON_BUILDER.comment("Whether synchronize chat").define("sync_chat", true);
        IS_CHAT_SERVER = COMMON_BUILDER.comment("Whether recieve messages from other servers as host").define("IsChatServer",false);
        CHAT_SERVER_IP = COMMON_BUILDER.define("ChatServerIP","127.0.0.1");
        CHAT_SERVER_PORT = COMMON_BUILDER.defineInRange("ChatServerPort",7900,0,65535);
        COMMON_BUILDER.pop();
        COMMON_CONFIG = COMMON_BUILDER.build();
    }
}

