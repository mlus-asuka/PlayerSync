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
import vip.fubuki.playersync.sync.ChatSync;
import vip.fubuki.playersync.sync.VanillaSync;
import vip.fubuki.playersync.util.JDBCsetUp;

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
        event.enqueueWork(() -> {
            // read SYNC_CHAT only within the enqueueWork to reliably get the real
            // config value and not its default value.
            if (JdbcConfig.SYNC_CHAT.get()) {
                LOGGER.info("Chat sync enabled.");
                ChatSync.register();
            }
        });
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) throws SQLException {
        String dbName = JdbcConfig.DATABASE_NAME.get();

        // FIX: Validate database name to prevent SQL injection via config.
        // Only alphanumeric chars and underscores are allowed in MySQL identifiers.
        if (!dbName.matches("[A-Za-z0-9_]+")) {
            LOGGER.error("Invalid DATABASE_NAME '{}'. Only alphanumeric characters and underscores are allowed. Aborting.", dbName);
            return;
        }

        // Step 1: Create the database using a raw DriverManager connection (no pool yet).
        JDBCsetUp.executeUpdate("CREATE DATABASE IF NOT EXISTS `" + dbName + "`", 1);

        // Step 2: Initialise HikariCP pool now that the database exists.
        // All subsequent queries use the pool — no more isValid() ping on every borrow.
        try {
            JDBCsetUp.initPool();
        } catch (Exception e) {
            LOGGER.error("[PlayerSync] Failed to initialise connection pool — check MySQL config.", e);
            return;
        }

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
                "CREATE TABLE IF NOT EXISTS `" + dbName + "`.`player_data` (" +
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
                "SELECT COUNT(*) AS column_count FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'player_data'",
                dbName)) {
            ResultSet resultSet = queryResult.resultSet();
            if (resultSet.next()) {
                columnCount = resultSet.getInt("column_count");
            }
        }
        if (columnCount < 14) {
            JDBCsetUp.executeUpdate(
                    "ALTER TABLE `" + dbName + "`.`player_data` " +
                            "ADD COLUMN left_hand blob, " +
                            "ADD COLUMN cursors blob;"
            );
        }

        // Create server_info table
        JDBCsetUp.executeUpdate(
                "CREATE TABLE IF NOT EXISTS `" + dbName + "`.`server_info` (" +
                        "`id` INT NOT NULL," +
                        "`enable` boolean NOT NULL," +
                        "`last_update` BIGINT NOT NULL," +
                        "PRIMARY KEY (`id`)" +
                        ");"
        );
        // FIX H-8: Use prepared statements for server_id to prevent SQL injection from config
        long current = System.currentTimeMillis();
        JDBCsetUp.executePreparedUpdate(
                "INSERT INTO `" + dbName + "`.`server_info`(id,enable,last_update) VALUES(?,true,?) ON DUPLICATE KEY UPDATE id=VALUES(id),enable=1,last_update=VALUES(last_update)",
                JdbcConfig.SERVER_ID.get(), current
        );
        JDBCsetUp.executePreparedUpdate(
                "UPDATE `" + dbName + "`.`server_info` SET last_update=? WHERE id=?",
                System.currentTimeMillis(), JdbcConfig.SERVER_ID.get()
        );

        // Create curios table if the Curios mod is loaded
        if (ModList.get().isLoaded("curios")) {
            JDBCsetUp.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS `" + dbName + "`.`curios` (" +
                            "uuid CHAR(36) NOT NULL, curios_item BLOB, PRIMARY KEY (uuid)" +
                            ")"
            );
        }

        // Create Cobblemon table
        if(ModList.get().isLoaded("cobblemon")){
            JDBCsetUp.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS `" + dbName + "`.`cobblemon`(" +
                            "uuid CHAR(36) NOT NULL," +
                            "inv BLOB," +
                            "pokedex MEDIUMBLOB," +
                            "pc MEDIUMBLOB," +
                            "general BLOB," +
                            "PRIMARY KEY (uuid)" +
                            ")"
            );

            JDBCsetUp.executeUpdate(
                    "ALTER TABLE `" + dbName + "`.`cobblemon` MODIFY COLUMN pc MEDIUMBLOB"
            );
             JDBCsetUp.executeUpdate(
                    "ALTER TABLE `" + dbName + "`.`cobblemon` MODIFY COLUMN pokedex MEDIUMBLOB"
            );
        }

        // Create backpack_data table
        if (ModList.get().isLoaded("sophisticatedbackpacks")) {
            JDBCsetUp.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS `" + dbName + "`.`backpack_data` (" +
                            "uuid CHAR(36) NOT NULL, backpack_nbt MEDIUMBLOB, PRIMARY KEY (uuid)" +
                            ");", 1
            );

            // Check if backpack_data table has the 'uuid' column
            try (JDBCsetUp.QueryResult backpackColCheck = JDBCsetUp.executePreparedQuery(
                    "SELECT COUNT(*) AS colCount FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'backpack_data' AND COLUMN_NAME = 'uuid'",
                    dbName)) {
                ResultSet rsBackpackCol = backpackColCheck.resultSet();
                if (rsBackpackCol.next() && rsBackpackCol.getInt("colCount") == 0) {
                    LOGGER.info("Altering backpack_data table to add missing 'uuid' column.");
                    JDBCsetUp.executeUpdate("ALTER TABLE `" + dbName + "`.`backpack_data` ADD COLUMN uuid CHAR(36) NOT NULL", 1);
                    JDBCsetUp.executeUpdate("ALTER TABLE `" + dbName + "`.`backpack_data` ADD PRIMARY KEY (uuid)", 1);
                }
            }
        }

        // Check and alter the 'advancements' column in player_data if necessary
        try (JDBCsetUp.QueryResult advColCheck = JDBCsetUp.executePreparedQuery(
                "SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'player_data' AND COLUMN_NAME = 'advancements'",
                dbName)) {
            ResultSet rsAdvCol = advColCheck.resultSet();
            if (rsAdvCol.next()) {
                String dataType = rsAdvCol.getString("DATA_TYPE");
                if (!"mediumblob".equalsIgnoreCase(dataType)) {
                    LOGGER.info("Altering player_data table to modify 'advancements' column to MEDIUMBLOB.");
                    JDBCsetUp.executeUpdate("ALTER TABLE `" + dbName + "`.`player_data` MODIFY COLUMN advancements MEDIUMBLOB", 1);
                }
            }
        }

        // Create generic mod_player_data table for mod compatibility (Accessories, CosmeticArmor, Aether, etc.)
        JDBCsetUp.executeUpdate(
                "CREATE TABLE IF NOT EXISTS `" + dbName + "`.`mod_player_data` (" +
                        "`uuid` CHAR(36) NOT NULL," +
                        "`mod_id` VARCHAR(64) NOT NULL," +
                        "`data_value` MEDIUMBLOB," +
                        "PRIMARY KEY (`uuid`, `mod_id`)" +
                        ");"
        );

        try {
            JDBCsetUp.executePreparedUpdate("UPDATE player_data SET online=0 WHERE last_server=? AND online=1", JdbcConfig.SERVER_ID.get());
        } catch (Exception e) {
            LOGGER.error("An exception occurred while trying change wrong player-status\n" + e.getMessage());
        }
        LOGGER.info("PlayerSync is ready!");
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        ChatSync.shutdown();
        vip.fubuki.playersync.util.SyncLogger.shutdown();
        // DO NOT call JDBCsetUp.shutdownPool() here!
        // VanillaSync.onServerShutdown also subscribes to ServerStoppingEvent and
        // needs the pool to save all player data. Event firing order is not guaranteed.
        // The pool is shut down at the very end of VanillaSync.onServerShutdown instead.
    }

}
