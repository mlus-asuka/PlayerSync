package vip.fubuki.playersync.config;


import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;


public class JdbcConfig {
    public static ModConfigSpec COMMON_CONFIG;

    // ----- Connection (kept under [general] for backward compat with existing config files) -----
    public static ModConfigSpec.ConfigValue<String> HOST;
    public static ModConfigSpec.IntValue PORT;
    public static ModConfigSpec.ConfigValue<String> USERNAME;
    public static ModConfigSpec.ConfigValue<String> PASSWORD;
    public static ModConfigSpec.ConfigValue<String> DATABASE_NAME;
    public static ModConfigSpec.BooleanValue USE_SSL;

    // ----- Core sync behaviour (kept under [general]) -----
    public static ModConfigSpec.ConfigValue<List<String>> SYNC_WORLD;
    public static ModConfigSpec.BooleanValue SYNC_ADVANCEMENTS;
    public static ModConfigSpec.BooleanValue KICK_WHEN_ALREADY_ONLINE;
    public static ModConfigSpec.ConfigValue<String> KICK_MESSAGE;
    public static ModConfigSpec.IntValue KICK_GRACE_PERIOD_MS;
    public static ModConfigSpec.BooleanValue USE_LEGACY_SERIALIZATION;
    public static final ModConfigSpec.ConfigValue<String> ITEM_PLACEHOLDER_TITLE_OVERRIDE;
    public static final ModConfigSpec.ConfigValue<String> ITEM_PLACEHOLDER_DESCRIPTION_OVERRIDE;

    public static ModConfigSpec.ConfigValue<Integer> SERVER_ID;

    /** Table-name prefix; see {@link vip.fubuki.playersync.util.Tables}. */
    public static ModConfigSpec.ConfigValue<String> TABLE_PREFIX;

    // ----- Save triggers (new section) -----
    public static ModConfigSpec.IntValue AUTO_SAVE_INTERVAL_MINUTES;
    public static ModConfigSpec.BooleanValue SAVE_ON_DIMENSION_CHANGE;
    public static ModConfigSpec.BooleanValue SAVE_ON_DEATH;
    public static ModConfigSpec.BooleanValue SAVE_ON_RESPAWN;

    // ----- Sync toggles (new section) -----
    public static ModConfigSpec.BooleanValue SYNC_INVENTORY;
    public static ModConfigSpec.BooleanValue SYNC_ENDER_CHEST;
    public static ModConfigSpec.BooleanValue SYNC_XP;
    public static ModConfigSpec.BooleanValue SYNC_EFFECTS;
    public static ModConfigSpec.BooleanValue SYNC_HEALTH_FOOD;
    public static ModConfigSpec.BooleanValue SYNC_CURIOS;
    public static ModConfigSpec.BooleanValue SYNC_ACCESSORIES;
    public static ModConfigSpec.BooleanValue SYNC_BACKPACKS;
    public static ModConfigSpec.BooleanValue SYNC_COSMETIC_ARMOR;
    public static ModConfigSpec.BooleanValue SYNC_REFINED_STORAGE;

    // ----- Performance tuning (new section) -----
    public static ModConfigSpec.IntValue HEARTBEAT_INTERVAL_SECONDS;
    public static ModConfigSpec.IntValue PEER_STALE_THRESHOLD_SECONDS;
    public static ModConfigSpec.IntValue JOIN_POLL_MAX_ATTEMPTS;
    public static ModConfigSpec.IntValue JOIN_POLL_INTERVAL_MS;
    public static ModConfigSpec.IntValue JOIN_PEER_ALIVE_MAX_WAIT_SECONDS;
    public static ModConfigSpec.IntValue POOL_STATS_INTERVAL_MINUTES;
    public static ModConfigSpec.IntValue HIKARI_POOL_MAX_SIZE;
    public static ModConfigSpec.IntValue HIKARI_LEAK_THRESHOLD_MS;

    // ----- Safety / integrity (new section) -----
    public static ModConfigSpec.BooleanValue REFUSE_EMPTY_INVENTORY_WRITE;
    public static ModConfigSpec.IntValue MAX_INVENTORY_SIZE_BYTES;
    public static ModConfigSpec.IntValue SKIP_SAVES_WHEN_TPS_BELOW;

    // ----- Observability (new section) -----
    public static ModConfigSpec.BooleanValue LOG_STRUCTURED_JSON;
    public static ModConfigSpec.IntValue LOG_ROTATION_SIZE_MB;
    public static ModConfigSpec.IntValue LOG_ROTATION_MAX_FILES;


    static {
        ModConfigSpec.Builder B = new ModConfigSpec.Builder();

        // ==========================================================================
        // [general] — Every key that already existed in pre-2.1.5 configs MUST stay
        // here so existing playersync-common.toml files keep working after an upgrade.
        // New settings go into dedicated sections below.
        // ==========================================================================
        B.comment("General settings").push("general");

        HOST = B.comment("The host of the database").define("host", "localhost");
        PORT = B.comment("database port").defineInRange("db_port", 3306, 0, 65535);
        USE_SSL = B.comment("whether use SSL").define("use_ssl", false);
        USERNAME = B.comment("username").define("user_name", "playersync");
        PASSWORD = B.comment("password").define("password", "pleaseChangeThisPassword");
        DATABASE_NAME = B.comment("database name").define("db_name", "playersync");
        TABLE_PREFIX = B.comment(
                "Optional prefix prepended to every PlayerSync table (player_data, curios, backpack_data, ...).",
                "Use to share a single MySQL database with other mods or legacy schemas.",
                "Leave empty to keep the historical unprefixed names. Example: 'playersync_'.",
                "Only alphanumeric characters and underscores are allowed."
            ).define("table_prefix", "");
        SERVER_ID = B.comment("the server id should be unique")
                .define("Server_id", new Random().nextInt(1, Integer.MAX_VALUE - 1));
        SYNC_WORLD = B.comment("The worlds that will be synchronized. If running on a server, leave array empty.")
                .define("sync_world", new ArrayList<>());
        SYNC_ADVANCEMENTS = B.comment("Whether to sync advancements between servers")
                .define("sync_advancements", true);
        KICK_WHEN_ALREADY_ONLINE = B.comment("Whether to kick player when already online on another server")
                .define("kick_when_already_online", true);
        // NEW in 2.1.5 — safe to add to [general], unknown keys on old rollbacks just get ignored.
        KICK_MESSAGE = B.comment(
                "Custom kick message when a duplicate login is detected. Empty = default message.")
                .define("kick_message", "");
        KICK_GRACE_PERIOD_MS = B.comment(
                "Milliseconds to wait before kicking a duplicate-login player. Short grace period lets",
                "the legitimate session re-establish on this server. Range 0-10000.")
                .defineInRange("kick_grace_period_ms", 500, 0, 10000);
        USE_LEGACY_SERIALIZATION = B.comment(
                "Use the old (pre-Base64) serialization format for writing data to the database.",
                "Set to true ONLY if you have older mod versions reading the same database.",
                "This only affects writing data, the mod can read both Base64 and pre-Base64 serialization.",
                "New installations should leave this as 'false'."
            ).define("use_legacy_serialization", false);
        ITEM_PLACEHOLDER_TITLE_OVERRIDE = B
                .comment("Override the title of placeholder items which are unavailable on the current server.")
                .define("item_placeholder_title_override", "");
        ITEM_PLACEHOLDER_DESCRIPTION_OVERRIDE = B
                .comment("Override the description of placeholder items which are unavailable on the current server.")
                .define("item_placeholder_description_override", "");

        B.pop(); // end [general]

        // ===== [save_triggers] =====
        B.comment("When to trigger a save (new in 2.1.5)").push("save_triggers");
        AUTO_SAVE_INTERVAL_MINUTES = B.comment(
                "Periodic full-flush interval (minutes). Triggers a complete save (player data +",
                "backpacks + SS + RS2) for every online player. Set to 0 to disable. Default 10."
            ).defineInRange("auto_save_interval_minutes", 10, 0, 1440);
        SAVE_ON_DIMENSION_CHANGE = B.comment(
                "Trigger a full save when a player changes dimension. Protects against mid-teleport",
                "crashes. Adds DB load proportional to travel frequency."
            ).define("save_on_dimension_change", false);
        SAVE_ON_DEATH = B.comment(
                "Trigger a pre-death snapshot on LivingDeathEvent (before items drop).",
                "Recovery insurance if the normal logout handler is skipped after death."
            ).define("save_on_death", true);
        SAVE_ON_RESPAWN = B.comment(
                "Trigger a save after player respawn to capture the post-death state immediately.")
                .define("save_on_respawn", true);
        B.pop();

        // ===== [sync_toggles] =====
        B.comment("Per-category sync toggles — disable individual data kinds if your server doesn't need them (new in 2.1.5)").push("sync_toggles");
        SYNC_INVENTORY = B.comment("Sync main inventory + armor + offhand").define("sync_inventory", true);
        SYNC_ENDER_CHEST = B.comment("Sync ender chest contents").define("sync_ender_chest", true);
        SYNC_XP = B.comment("Sync total XP / experience levels").define("sync_xp", true);
        SYNC_EFFECTS = B.comment("Sync active potion effects").define("sync_effects", true);
        SYNC_HEALTH_FOOD = B.comment("Sync current health and food level").define("sync_health_food", true);
        SYNC_CURIOS = B.comment("Sync Curios API slots (if the Curios mod is installed)").define("sync_curios", true);
        SYNC_ACCESSORIES = B.comment("Sync Accessories API slots (if installed)").define("sync_accessories", true);
        SYNC_BACKPACKS = B.comment("Sync Sophisticated Backpacks + Storage contents").define("sync_backpacks", true);
        SYNC_COSMETIC_ARMOR = B.comment("Sync Cosmetic Armor Reworked slots").define("sync_cosmetic_armor", true);
        SYNC_REFINED_STORAGE = B.comment("Sync Refined Storage 2 disk contents").define("sync_refined_storage", true);
        B.pop();

        // ===== [performance] =====
        B.comment("Performance tuning — touch only if you know what you're doing (new in 2.1.5)").push("performance");
        HEARTBEAT_INTERVAL_SECONDS = B.comment(
                "How often this server writes its heartbeat to server_info (seconds). Pair with",
                "peer_stale_threshold_seconds: peers older than threshold are treated as dead.")
                .defineInRange("heartbeat_interval_seconds", 30, 5, 600);
        PEER_STALE_THRESHOLD_SECONDS = B.comment(
                "How old a peer heartbeat must be before we treat it as a dead (zombie) server.",
                "doPlayerJoin short-circuits the last_server poll when the peer is stale.")
                .defineInRange("peer_stale_threshold_seconds", 60, 10, 3600);
        JOIN_POLL_MAX_ATTEMPTS = B.comment(
                "Max attempts for doPlayerJoin's last_server poll before giving up.")
                .defineInRange("join_poll_max_attempts", 120, 10, 600);
        JOIN_POLL_INTERVAL_MS = B.comment(
                "Wait interval between last_server poll attempts (milliseconds).")
                .defineInRange("join_poll_interval_ms", 500, 100, 5000);
        JOIN_PEER_ALIVE_MAX_WAIT_SECONDS = B.comment(
                "When the previous server is ALIVE (heartbeat fresh) but the player row still",
                "shows online=1 on it, how long to wait before force-claiming ownership on this",
                "server. Prevents the 30-60s 'empty inventory' window when a player active on",
                "peer A connects to peer B without cleanly logging out (proxy, network drop,",
                "dup session). After this timeout, peer A will simply fail to save this player",
                "(blocked by last_server guard) and their next disconnect won't overwrite B's",
                "data. Default 5s. Set to 0 to force-claim immediately; set high to restore the",
                "legacy behavior of waiting for the peer to flush.")
                .defineInRange("join_peer_alive_max_wait_seconds", 5, 0, 600);
        POOL_STATS_INTERVAL_MINUTES = B.comment(
                "How often PoolStatsReporter logs executor + Hikari stats. 0 to disable.")
                .defineInRange("pool_stats_interval_minutes", 5, 0, 1440);
        HIKARI_POOL_MAX_SIZE = B.comment(
                "Max HikariCP connections. Empirical rule: cores*2 + spindles. Default 15 is good",
                "for typical 35-player servers on modest hardware.")
                .defineInRange("hikari_pool_max_size", 15, 1, 200);
        HIKARI_LEAK_THRESHOLD_MS = B.comment(
                "Hikari leak-detection threshold (ms). Lower = more sensitive, but false positives on",
                "slow polls. 25000 covers legitimate 15-30s poll bursts.")
                .defineInRange("hikari_leak_threshold_ms", 25000, 2000, 600000);
        B.pop();

        // ===== [safety] =====
        B.comment("Safety guards — prevent silent data loss (new in 2.1.5)").push("safety");
        REFUSE_EMPTY_INVENTORY_WRITE = B.comment(
                "Refuse to UPDATE player_data with an empty inventory if the DB currently has non-empty",
                "data. Last-resort guard against on-disconnect wipes. Set to false only for debugging.")
                .define("refuse_empty_inventory_write", true);
        MAX_INVENTORY_SIZE_BYTES = B.comment(
                "Max serialized inventory size (bytes). Snapshots larger than this are rejected with",
                "a log entry. Protects against infinite-NBT exploits. Default 10 MB.")
                .defineInRange("max_inventory_size_bytes", 10 * 1024 * 1024, 1024, 512 * 1024 * 1024);
        SKIP_SAVES_WHEN_TPS_BELOW = B.comment(
                "Skip periodic auto-saves when the server MSPT average exceeds the value implied by this",
                "TPS threshold. 0 = never skip. Example: 15 skips periodic saves when TPS < 15.")
                .defineInRange("skip_saves_when_tps_below", 0, 0, 20);
        B.pop();

        // ===== [observability] =====
        B.comment("Log file & diagnostics (new in 2.1.5)").push("observability");
        LOG_STRUCTURED_JSON = B.comment(
                "Emit sync.log entries as JSON objects instead of text. Enables ingestion in",
                "Loki / ELK / Splunk pipelines.")
                .define("log_structured_json", false);
        LOG_ROTATION_SIZE_MB = B.comment(
                "Max sync.log size before rotation (megabytes).")
                .defineInRange("log_rotation_size_mb", 10, 1, 1024);
        LOG_ROTATION_MAX_FILES = B.comment(
                "Keep at most N rotated sync.log files (oldest deleted).")
                .defineInRange("log_rotation_max_files", 5, 1, 100);
        B.pop();

        COMMON_CONFIG = B.build();
    }
}
