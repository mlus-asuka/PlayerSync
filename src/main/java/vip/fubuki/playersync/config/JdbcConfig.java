package vip.fubuki.playersync.config;


import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;


public class JdbcConfig {
    public static ModConfigSpec COMMON_CONFIG;
    public static ModConfigSpec.ConfigValue<String> HOST;
    public static ModConfigSpec.IntValue PORT;
    public static ModConfigSpec.ConfigValue<String> USERNAME;
    public static ModConfigSpec.ConfigValue<String> PASSWORD;
    public static ModConfigSpec.ConfigValue<String> DATABASE_NAME;
    public static ModConfigSpec.ConfigValue<List<String>> SYNC_WORLD;
    public static ModConfigSpec.BooleanValue SYNC_ADVANCEMENTS;
    public static ModConfigSpec.BooleanValue USE_SSL;
    public static ModConfigSpec.BooleanValue SYNC_CHAT;
    public static ModConfigSpec.BooleanValue IS_CHAT_SERVER;
    public static final ModConfigSpec.ConfigValue<String> ITEM_PLACEHOLDER_TITLE_OVERRIDE;
    public static final ModConfigSpec.ConfigValue<String> ITEM_PLACEHOLDER_DESCRIPTION_OVERRIDE;
    public static ModConfigSpec.ConfigValue<String> CHAT_SERVER_IP;
    public static ModConfigSpec.IntValue CHAT_SERVER_PORT;
    public static ModConfigSpec.BooleanValue USE_LEGACY_SERIALIZATION;

    public static ModConfigSpec.ConfigValue<Integer> SERVER_ID;


    static {
        ModConfigSpec.Builder COMMON_BUILDER = new ModConfigSpec.Builder();
        COMMON_BUILDER.comment("General settings").push("general");
        HOST=COMMON_BUILDER.comment("The host of the database").define("host", "localhost");
        PORT = COMMON_BUILDER.comment("database port").defineInRange("db_port", 3306, 0, 65535);
        USE_SSL = COMMON_BUILDER.comment("whether use SSL").define("use_ssl", false);
        USERNAME = COMMON_BUILDER.comment("username").define("user_name", "playersync");
        PASSWORD = COMMON_BUILDER.comment("password").define("password", "pleaseChangeThisPassword");
        DATABASE_NAME = COMMON_BUILDER.comment("database name").define("db_name","playersync");
        SERVER_ID = COMMON_BUILDER.comment("the server id should be unique").define("Server_id", new Random().nextInt(1,Integer.MAX_VALUE-1));
        SYNC_WORLD = COMMON_BUILDER.comment("The worlds that will be synchronized. If running on a server, leave array empty.").define("sync_world", new ArrayList<>());
        SYNC_ADVANCEMENTS = COMMON_BUILDER.comment("Whether to sync advancements between servers")
                .define("sync_advancements", true);
        SYNC_CHAT = COMMON_BUILDER.comment("Whether synchronize chat").define("sync_chat", false);
        IS_CHAT_SERVER = COMMON_BUILDER.comment("Whether recieve messages from other servers as host").define("IsChatServer",false);
        CHAT_SERVER_IP = COMMON_BUILDER.define("ChatServerIP","127.0.0.1");
        CHAT_SERVER_PORT = COMMON_BUILDER.defineInRange("ChatServerPort",7900,0,65535);
        USE_LEGACY_SERIALIZATION = COMMON_BUILDER.comment(
                "Use the old (pre-Base64) serialization format for writing data to the database.",
                "Set to true ONLY if you have older mod versions reading the same database.",
                "This only affects writing data, the mod can read both Base64 and pre-Base64 serialization.",
                "New installations should leave this as 'false'."
            ).define("use_legacy_serialization", false);
        ITEM_PLACEHOLDER_TITLE_OVERRIDE = COMMON_BUILDER
                .comment("Override the title of placeholder items which are unavailable on the current server.")
                .define("item_placeholder_title_override", "");
        ITEM_PLACEHOLDER_DESCRIPTION_OVERRIDE = COMMON_BUILDER
                .comment("Override the description of placeholder items which are unavailable on the current server.")
                .define("item_placeholder_description_override", "");

        COMMON_BUILDER.pop();
        COMMON_CONFIG = COMMON_BUILDER.build();
    }
}
