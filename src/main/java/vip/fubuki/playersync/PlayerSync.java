package vip.fubuki.playersync;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;
import vip.fubuki.playersync.config.JdbcConfig;
import vip.fubuki.playersync.sync.VanillaSync;
import vip.fubuki.playersync.util.JDBCsetUp;
import vip.fubuki.playersync.util.Tables;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

@Mod(PlayerSync.MODID)
public class PlayerSync {
    public static final String MODID = "playersync";
    public static final Logger LOGGER = LogUtils.getLogger();

    public PlayerSync(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);
        NeoForge.EVENT_BUS.register(this);
        modContainer.registerConfig(ModConfig.Type.COMMON, JdbcConfig.COMMON_CONFIG);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        VanillaSync.register();
        // Chat sync removed. The `sync_chat` / `IsChatServer` / `ChatServerIP` /
        // `ChatServerPort` keys in existing config files are now silently ignored
        // (NeoForge's ModConfig loader skips unknown keys, so no crash on upgrade).
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        // FIX COMPAT (C2): skip all MySQL init on single-player / integrated servers.
        // Running PlayerSync in single-player makes no sense (no cross-server sync) and
        // attempting to open a MySQL connection with default placeholder credentials on a
        // laptop without a MySQL server produces noisy errors + degraded UX.
        if (!event.getServer().isDedicatedServer()) {
            LOGGER.info("PlayerSync: integrated server detected — skipping MySQL init (dedicated-server only).");
            return;
        }

        // Full init guarded by a single try/catch so a missing / unreachable MySQL
        // prints a user-friendly tutorial in the console instead of crashing the
        // dedicated server or flooding the log with a raw JDBC stack trace.
        try {
            onServerStartingUnchecked(event);
        } catch (Throwable t) {
            printDatabaseTutorialBanner(t);
        }
    }

    private void onServerStartingUnchecked(ServerStartingEvent event) throws SQLException {
        String dbName = JdbcConfig.DATABASE_NAME.get();

        // FIX: Validate database name to prevent SQL injection via config.
        // Only alphanumeric chars and underscores are allowed in MySQL identifiers.
        if (!dbName.matches("[A-Za-z0-9_]+")) {
            LOGGER.error("Invalid DATABASE_NAME '{}'. Only alphanumeric characters and underscores are allowed. Aborting.", dbName);
            throw new SQLException("Invalid DATABASE_NAME: " + dbName);
        }

        // Detect placeholder credentials and surface a tutorial straight away.
        String pass = JdbcConfig.PASSWORD.get();
        String host = JdbcConfig.HOST.get();
        if ("pleaseChangeThisPassword".equals(pass) || "localhost".equals(host)) {
            LOGGER.warn("[PlayerSync] Using placeholder credentials (host={}, password={}). Attempting anyway; a tutorial will be printed if the connection fails.",
                    host, "pleaseChangeThisPassword".equals(pass) ? "<DEFAULT>" : "<set>");
        }

        // Step 1: Create the database using a raw DriverManager connection (no pool yet).
        JDBCsetUp.executeUpdate("CREATE DATABASE IF NOT EXISTS `" + dbName + "`", 1);

        // Step 2: Initialise HikariCP pool now that the database exists.
        JDBCsetUp.initPool();

        // Initialize dedicated PlayerSync log file (logs/playersync/sync.log)
        vip.fubuki.playersync.util.SyncLogger.init();

        // Step 3: Explicitly select the database on a raw connection (DDL only).
        try (Connection conn = JDBCsetUp.getConnection(false);
             Statement st = conn.createStatement()) {
            st.execute("USE `" + dbName + "`");
        } catch (SQLException e) {
            LOGGER.error("Error selecting database " + dbName, e);
            throw e;
        }

        // Step 4: Create and alter tables using fully qualified names.
        // Create player_data table
        JDBCsetUp.executeUpdate(
                "CREATE TABLE IF NOT EXISTS `" + dbName + "`.`" + Tables.playerData() + "` (" +
                        "`uuid` char(36) NOT NULL," +
                        "`inventory` mediumblob," +
                        "`armor` blob," +
                        "`advancements` blob," +
                        "`enderchest` mediumblob," +
                        "`effects` blob," +
                        "`left_hand` blob," +
                        "`cursors` blob," +
                        "`xp` int DEFAULT NULL," +
                        "`food_level` int DEFAULT NULL," +
                        "`score` int DEFAULT NULL," +
                        "`health` int DEFAULT NULL," +
                        "`online` tinyint(1) DEFAULT NULL," +
                        "`last_server` int DEFAULT NULL," +
                        "PRIMARY KEY (`uuid`)" +
                        ");"
        );

        // Check and alter player_data table if columns are missing
        int columnCount = 0;
        try (JDBCsetUp.QueryResult queryResult = JDBCsetUp.executePreparedQuery(
                "SELECT COUNT(*) AS column_count FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?",
                dbName, Tables.playerData())) {
            ResultSet resultSet = queryResult.resultSet();
            if (resultSet.next()) {
                columnCount = resultSet.getInt("column_count");
            }
        }
        if (columnCount < 14) {
            JDBCsetUp.executeUpdate(
                    "ALTER TABLE `" + dbName + "`.`" + Tables.playerData() + "` " +
                            "ADD COLUMN left_hand blob, " +
                            "ADD COLUMN cursors blob;"
            );
        }

        // Create server_info table
        JDBCsetUp.executeUpdate(
                "CREATE TABLE IF NOT EXISTS `" + dbName + "`.`" + Tables.serverInfo() + "` (" +
                        "`id` INT NOT NULL," +
                        "`enable` boolean NOT NULL," +
                        "`last_update` BIGINT NOT NULL," +
                        "PRIMARY KEY (`id`)" +
                        ");"
        );
        // FIX H-8: Use prepared statements for server_id to prevent SQL injection from config
        long current = System.currentTimeMillis();
        JDBCsetUp.executePreparedUpdate(
                "INSERT INTO `" + dbName + "`.`" + Tables.serverInfo() + "`(id,enable,last_update) VALUES(?,true,?) ON DUPLICATE KEY UPDATE id=VALUES(id),enable=1,last_update=VALUES(last_update)",
                JdbcConfig.SERVER_ID.get(), current
        );
        JDBCsetUp.executePreparedUpdate(
                "UPDATE `" + dbName + "`.`" + Tables.serverInfo() + "` SET last_update=? WHERE id=?",
                System.currentTimeMillis(), JdbcConfig.SERVER_ID.get()
        );

        // Create curios table if the Curios mod is loaded
        if (ModList.get().isLoaded("curios")) {
            JDBCsetUp.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS `" + dbName + "`.`" + Tables.curios() + "` (" +
                            "uuid CHAR(36) NOT NULL, curios_item BLOB, PRIMARY KEY (uuid)" +
                            ")"
            );
        }

        // Cobblemon support removed in this build (sync was main-thread blocking + SQL
        // injection in the mixins). Existing `cobblemon` tables in the DB are kept intact
        // for backward compat — they are simply no longer read or written.

        // Create backpack_data table
        if (ModList.get().isLoaded("sophisticatedbackpacks")) {
            JDBCsetUp.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS `" + dbName + "`.`" + Tables.backpackData() + "` (" +
                            "uuid CHAR(36) NOT NULL, backpack_nbt MEDIUMBLOB, PRIMARY KEY (uuid)" +
                            ");", 1
            );

            // Check if backpack_data table has the 'uuid' column
            try (JDBCsetUp.QueryResult backpackColCheck = JDBCsetUp.executePreparedQuery(
                    "SELECT COUNT(*) AS colCount FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND COLUMN_NAME = 'uuid'",
                    dbName, Tables.backpackData())) {
                ResultSet rsBackpackCol = backpackColCheck.resultSet();
                if (rsBackpackCol.next() && rsBackpackCol.getInt("colCount") == 0) {
                    LOGGER.info("Altering backpack_data table to add missing 'uuid' column.");
                    JDBCsetUp.executeUpdate("ALTER TABLE `" + dbName + "`.`" + Tables.backpackData() + "` ADD COLUMN uuid CHAR(36) NOT NULL", 1);
                    JDBCsetUp.executeUpdate("ALTER TABLE `" + dbName + "`.`" + Tables.backpackData() + "` ADD PRIMARY KEY (uuid)", 1);
                }
            }
        }

        // Check and alter the 'advancements' column in player_data if necessary
        try (JDBCsetUp.QueryResult advColCheck = JDBCsetUp.executePreparedQuery(
                "SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND COLUMN_NAME = 'advancements'",
                dbName, Tables.playerData())) {
            ResultSet rsAdvCol = advColCheck.resultSet();
            if (rsAdvCol.next()) {
                String dataType = rsAdvCol.getString("DATA_TYPE");
                if (!"mediumblob".equalsIgnoreCase(dataType)) {
                    LOGGER.info("Altering player_data table to modify 'advancements' column to MEDIUMBLOB.");
                    JDBCsetUp.executeUpdate("ALTER TABLE `" + dbName + "`.`" + Tables.playerData() + "` MODIFY COLUMN advancements MEDIUMBLOB", 1);
                }
            }
        }

        // Create generic mod_player_data table for mod compatibility (Accessories, CosmeticArmor, Aether, etc.)
        JDBCsetUp.executeUpdate(
                "CREATE TABLE IF NOT EXISTS `" + dbName + "`.`" + Tables.modPlayerData() + "` (" +
                        "`uuid` CHAR(36) NOT NULL," +
                        "`mod_id` VARCHAR(64) NOT NULL," +
                        "`data_value` MEDIUMBLOB," +
                        "PRIMARY KEY (`uuid`, `mod_id`)" +
                        ");"
        );

        try {
            JDBCsetUp.executePreparedUpdate("UPDATE " + Tables.playerData() + " SET online=0 WHERE last_server=? AND online=1", JdbcConfig.SERVER_ID.get());
        } catch (Exception e) {
            LOGGER.error("An exception occurred while trying change wrong player-status\n" + e.getMessage());
        }

        // Phase 3: anti-loss infrastructure.
        //   1. Clear orphaned online=1 flags from previous unclean shutdown.
        //   2. Report zombie peer servers so admins see them in logs.
        //   3. Install JVM shutdown hook — covers kill -9 / OOM / host reboot.
        //   4. Start periodic heartbeat so peers can detect us as alive.
        vip.fubuki.playersync.util.CrashRecovery.clearOrphanedOnlineFlags();
        vip.fubuki.playersync.util.CrashRecovery.reportZombiePeers(60_000L);
        vip.fubuki.playersync.util.CrashRecovery.installShutdownHook(() ->
                vip.fubuki.playersync.sync.VanillaSync.emergencyFlushAll());
        vip.fubuki.playersync.util.HeartbeatService.start();

        // Phase 4: periodic full-flush scheduler (default 10 min).
        vip.fubuki.playersync.util.PeriodicSaveService.start();

        // Phase 5: pool / executor stats reporter (every 5 min into sync.log).
        vip.fubuki.playersync.util.PoolStatsReporter.start();

        LOGGER.info("PlayerSync is ready!");
    }

    /**
     * Alters a column to {@code targetType} only if its current {@code DATA_TYPE}
     * differs. Skips expensive MDL + rebuild on every server start.
     */
    private static void alterColumnIfNeeded(String dbName, String table, String column, String targetTypeLower) throws SQLException {
        try (JDBCsetUp.QueryResult qr = JDBCsetUp.executePreparedQuery(
                "SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND COLUMN_NAME=?",
                dbName, table, column)) {
            ResultSet rs = qr.resultSet();
            if (rs.next()) {
                String current = rs.getString("DATA_TYPE");
                if (current != null && targetTypeLower.equalsIgnoreCase(current)) {
                    return;
                }
            }
        }
        LOGGER.info("Altering {}.{} column {} to {}", dbName, table, column, targetTypeLower.toUpperCase());
        JDBCsetUp.executeUpdate("ALTER TABLE `" + dbName + "`.`" + table + "` MODIFY COLUMN `" + column + "` " + targetTypeLower.toUpperCase());
    }

    /**
     * Prints a big, friendly banner to the console explaining why PlayerSync could
     * not initialise its database. Invoked from the top-level try/catch in
     * {@link #onServerStarting(ServerStartingEvent)} so the dedicated server boots
     * anyway — admins running the mod for the first time get a tutorial instead
     * of a cryptic SQLException.
     */
    private static void printDatabaseTutorialBanner(Throwable failure) {
        String configPath = "config/playersync-common.toml";
        String host = safe(JdbcConfig.HOST);
        int port = safeInt(JdbcConfig.PORT, 3306);
        String user = safe(JdbcConfig.USERNAME);
        String db = safe(JdbcConfig.DATABASE_NAME);
        boolean defaultPass = "pleaseChangeThisPassword".equals(safe(JdbcConfig.PASSWORD));
        String rootCause = rootCauseSummary(failure);

        String[] banner = {
                "",
                "######################################################################",
                "#                                                                    #",
                "#   PlayerSync — DATABASE NOT AVAILABLE — SERVER STILL STARTED       #",
                "#                                                                    #",
                "#   PlayerSync requires a MySQL / MariaDB database to sync player    #",
                "#   data across servers. Your server will BOOT without sync until    #",
                "#   the connection is fixed.                                         #",
                "#                                                                    #",
                "######################################################################",
                "",
                "What failed: " + rootCause,
                "",
                "Current config (from " + configPath + "):",
                "    host      = " + host,
                "    db_port   = " + port,
                "    user_name = " + user,
                "    db_name   = " + db,
                "    password  = " + (defaultPass ? "<PLACEHOLDER — NOT CHANGED>" : "<set>"),
                "",
                "=== Quick-fix checklist ===",
                "  1. Is the database reachable from this host?",
                "       telnet " + host + " " + port + "          (should connect)",
                "       mysql -h " + host + " -P " + port + " -u " + user + " -p",
                "",
                "  2. Did you change the password in " + configPath + " ?",
                (defaultPass
                        ? "       >> NO — you're using the default 'pleaseChangeThisPassword'. <<"
                        : "       OK — password is set."),
                "",
                "  3. Running on localhost for dev? Use the bundled Docker compose:",
                "       docker compose up -d            # project root",
                "       (starts MariaDB + Adminer on :3306 / :8080)",
                "",
                "  4. Firewall / bind-address?  MySQL config 'bind-address = 0.0.0.0'",
                "     and the user must have remote-login grants:",
                "       GRANT ALL ON " + db + ".* TO '" + user + "'@'%' IDENTIFIED BY '<your-pw>';",
                "       FLUSH PRIVILEGES;",
                "",
                "  5. Completely disable PlayerSync for this session — remove the jar",
                "     or start with -Dplayersync.disabled=true (not enforced by the mod",
                "     itself, but skips noisy errors if you don't intend to use it).",
                "",
                "Full exception trace follows for support / bug reports:",
                "######################################################################",
                "",
        };
        for (String line : banner) {
            LOGGER.error(line);
        }
        LOGGER.error("PlayerSync initialisation failed — root cause:", failure);
        LOGGER.error("######################################################################");
    }

    private static String safe(net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<?> v) {
        try { Object o = v.get(); return o == null ? "<null>" : o.toString(); } catch (Throwable t) { return "<unreadable>"; }
    }
    private static int safeInt(net.neoforged.neoforge.common.ModConfigSpec.IntValue v, int def) {
        try { return v.get(); } catch (Throwable t) { return def; }
    }
    private static String rootCauseSummary(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        String cls = cur.getClass().getSimpleName();
        String msg = cur.getMessage() == null ? "(no message)" : cur.getMessage().replaceAll("\\s+", " ").trim();
        if (msg.length() > 180) msg = msg.substring(0, 177) + "...";
        return cls + ": " + msg;
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        // DO NOT call JDBCsetUp.shutdownPool() or SyncLogger.shutdown() here!
        // VanillaSync.onServerShutdown also subscribes to ServerStoppingEvent and
        // needs the pool to save all player data AND the logger to trace those saves.
        // NeoForge does not guarantee handler ordering across @SubscribeEvent instances,
        // so both the pool and the logger are shut down at the very end of
        // VanillaSync.onServerShutdown — after parallel saves finish.
    }

}
