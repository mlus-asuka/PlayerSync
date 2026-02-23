package vip.fubuki.playersync;

import com.mojang.logging.LogUtils;
import com.mysql.cj.jdbc.Driver;
import net.minecraft.SharedConstants;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import vip.fubuki.playersync.config.JdbcConfig;
import vip.fubuki.playersync.sync.ChatSync;
import vip.fubuki.playersync.sync.VanillaSync;
import vip.fubuki.playersync.util.JDBCsetUp;

import java.sql.*;

@Mod(PlayerSync.MODID)
public class PlayerSync {
    public static final String MODID = "playersync";
    public static final Logger LOGGER = LogUtils.getLogger();

    public PlayerSync(FMLJavaModLoadingContext context) {
        IEventBus modEventBus = context.getModEventBus();
        context.registerConfig(ModConfig.Type.COMMON, JdbcConfig.COMMON_CONFIG);
        modEventBus.addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        // JDBC driver auto-detection is broken in Forge as of v47.4.0
        // We need to register the driver manually
        try {
            DriverManager.registerDriver(new Driver());
        } catch (SQLException e) {
            LOGGER.error("Unable to register JDBC MySQL driver", e);
        }

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

        // Step 1: Create the database using a connection that does not select a database.
        JDBCsetUp.executeUpdateWithoutDatabase("CREATE DATABASE IF NOT EXISTS `" + dbName + "`");

        // Step 2: Explicitly select the database on a connection obtained without default database.
        try (Connection conn = JDBCsetUp.getConnection(false);
             Statement st = conn.createStatement()) {
            st.execute("USE `" + dbName + "`");
        } catch (SQLException e) {
            LOGGER.error("Error selecting database " + dbName, e);
            throw e;
        }

        // Step 3: Create and alter tables using fully qualified names.
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
        JDBCsetUp.QueryResult queryResult = JDBCsetUp.executeQuery(
                "SELECT COUNT(*) AS column_count " +
                        "FROM INFORMATION_SCHEMA.COLUMNS " +
                        "WHERE TABLE_SCHEMA = '" + dbName + "' " +
                        "AND TABLE_NAME = 'player_data';"
        );
        ResultSet resultSet = queryResult.resultSet();
        int columnCount = 0;
        if (resultSet.next()) {
            columnCount = resultSet.getInt("column_count");
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
        // do not modify the create table statement to make sure this code is compatible with older database versions
        addColumnIfNotExists("server_info", "data_version", "INT NOT NULL DEFAULT 0");

        long current = System.currentTimeMillis();
        int data_version = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
        JDBCsetUp.executeUpdate("""
                INSERT INTO `%s`.`server_info`
                (
                    id,
                    enable,
                    data_version,
                    last_update
                )
                VALUES (
                    %d,
                    true,
                    %d,
                    %d
                )
                ON DUPLICATE KEY UPDATE
                    id = %d,
                    enable = true,
                    data_version = %d,
                    last_update = %d;
                """,
                dbName,
                JdbcConfig.SERVER_ID.get(),
                data_version,
                current,
                JdbcConfig.SERVER_ID.get(),
                data_version,
                current);

        // Create curios table if the Curios mod is loaded
        if (ModList.get().isLoaded("curios")) {
            JDBCsetUp.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS `" + dbName + "`.`curios` (" +
                            "uuid CHAR(36) NOT NULL, curios_item BLOB, PRIMARY KEY (uuid)" +
                            ")"
            );
        }

        // Create backpack_data table
        if (ModList.get().isLoaded("sophisticatedbackpacks")) {
            JDBCsetUp.executeUpdateWithoutDatabase(
                    "CREATE TABLE IF NOT EXISTS `" + dbName + "`.`backpack_data` (" +
                            "uuid CHAR(36) NOT NULL, backpack_nbt MEDIUMBLOB, PRIMARY KEY (uuid)" +
                            ");"
            );
            // Check if backpack_data table has the 'uuid' column
            addColumnIfNotExists("backpack_data", "uuid", "CHAR(36) NOT NULL", true);
        }

        // Check and alter the 'advancements' column in player_data if necessary
        JDBCsetUp.QueryResult advColCheck = JDBCsetUp.executeQuery(
                "SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS " +
                        "WHERE TABLE_SCHEMA = '" + dbName + "' " +
                        "AND TABLE_NAME = 'player_data' " +
                        "AND COLUMN_NAME = 'advancements';"
        );
        ResultSet rsAdvCol = advColCheck.resultSet();
        if (rsAdvCol.next()) {
            String dataType = rsAdvCol.getString("DATA_TYPE");
            if (!"mediumblob".equalsIgnoreCase(dataType)) {
                LOGGER.info("Altering player_data table to modify 'advancements' column from {} to MEDIUMBLOB.", dataType);
                JDBCsetUp.executeUpdateWithoutDatabase("ALTER TABLE `" + dbName + "`.`player_data` MODIFY COLUMN advancements MEDIUMBLOB");
            }
        }
        rsAdvCol.close();
        // ----- END NEW BLOCK -----

        try {
            JDBCsetUp.executeUpdate("UPDATE player_data SET online=0 WHERE last_server=" + JdbcConfig.SERVER_ID.get() +" AND online=1 LIMIT 1000");
        } catch (Exception e) {
            LOGGER.error("An exception occurred while trying change wrong player-status\n" + e.getMessage());
        }
        LOGGER.info("PlayerSync is ready!");
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event){
        ChatSync.shutdown();
    }

    private static void addColumnIfNotExists(String tableName, String columnName, String dataTypeDefaultNullness,
            boolean makePrimaryKey) throws SQLException {

        // Making use of the AutoCloseable QueryResult here
        try (JDBCsetUp.QueryResult backpackColCheck = JDBCsetUp.executeQuery(
                "SELECT COUNT(*) AS colCount FROM INFORMATION_SCHEMA.COLUMNS " +
                        "WHERE TABLE_SCHEMA = DATABASE()" +
                        "AND TABLE_NAME = '" + tableName + "' " +
                        "AND COLUMN_NAME = '" + columnName + "';")) {
            ResultSet rsBackpackCol = backpackColCheck.resultSet();

            if (!rsBackpackCol.next()) {
                LOGGER.warn("Warning: Unable to check existence of colum {} in table {}.", columnName, tableName);
                return;
            }

            if (rsBackpackCol.getInt("colCount") > 0) {
                LOGGER.debug("Column {} already exists. Skipping creation.", columnName);
                return;
            }
        }

        LOGGER.info("ALTER {} table to add missing {} column.", tableName, columnName);
        // Add the missing column and set it as primary key.
        JDBCsetUp.executeUpdate(
                "ALTER TABLE %s ADD COLUMN %s %s",
                tableName, columnName, dataTypeDefaultNullness);

        if (makePrimaryKey) {
            LOGGER.info("Altering {} table to add primary key on {}.", tableName, columnName);
            JDBCsetUp.executeUpdate(
                    "ALTER TABLE %s ADD PRIMARY KEY (%s)",
                    tableName, columnName);
        }
    }

    private static void addColumnIfNotExists(String tableName, String columnName,
            String dataTypeDefaultNullness) throws SQLException {
        addColumnIfNotExists(tableName, columnName, dataTypeDefaultNullness, false);
    }
}
