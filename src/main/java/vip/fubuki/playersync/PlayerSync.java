package vip.fubuki.playersync;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
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

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

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
        VanillaSync.register();
        if (JdbcConfig.SYNC_CHAT.get()) {
            ChatSync.register();
        }
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) throws SQLException {
        String dbName = JdbcConfig.DATABASE_NAME.get();

        // Step 1: Create the database using a connection that does not select a database.
        JDBCsetUp.executeUpdate("CREATE DATABASE IF NOT EXISTS " + dbName, 1);

        // Step 2: Explicitly select the database on a connection obtained without default database.
        try (Connection conn = JDBCsetUp.getConnection(false);
             Statement st = conn.createStatement()) {
            st.execute("USE " + dbName);
        } catch (SQLException e) {
            LOGGER.error("Error selecting database " + dbName, e);
            throw e;
        }

        // Step 3: Create and alter tables using fully qualified names.
        // Create player_data table
        JDBCsetUp.executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + dbName + ".`player_data` (" +
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
                    "ALTER TABLE " + dbName + ".player_data " +
                            "ADD COLUMN left_hand blob, " +
                            "ADD COLUMN cursors blob;"
            );
        }

        // Create server_info table
        JDBCsetUp.executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + dbName + ".server_info (" +
                        "`id` INT NOT NULL," +
                        "`enable` boolean NOT NULL," +
                        "`last_update` BIGINT NOT NULL," +
                        "PRIMARY KEY (`id`)" +
                        ");"
        );
        long current = System.currentTimeMillis();
        JDBCsetUp.executeUpdate(
                "INSERT INTO " + dbName + ".server_info(id,enable,last_update) " +
                        "VALUES(" + JdbcConfig.SERVER_ID.get() + ",true," + current + ") " +
                        "ON DUPLICATE KEY UPDATE id= " + JdbcConfig.SERVER_ID.get() + ",enable = 1," +
                        "last_update=" + current + ";"
        );
        JDBCsetUp.executeUpdate(
                "UPDATE " + dbName + ".server_info SET last_update=" + System.currentTimeMillis() +
                        " WHERE id='" + JdbcConfig.SERVER_ID.get() + "'"
        );

        // Create curios table if the Curios mod is loaded
        if (ModList.get().isLoaded("curios")) {
            JDBCsetUp.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS " + dbName + ".curios (" +
                            "uuid CHAR(36) NOT NULL, curios_item BLOB, PRIMARY KEY (uuid)" +
                            ")"
            );
        }

        // Create backpack_data table
        if(ModList.get().isLoaded("sophisticatedbackpacks")){
            JDBCsetUp.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS " + dbName + ".backpack_data (" +
                            "uuid CHAR(36) NOT NULL, backpack_nbt MEDIUMBLOB, PRIMARY KEY (uuid)" +
                            ");", 1
            );
        }

        // ----- NEW BLOCK: Schema Update for backpack_data and player_data -----
        // Check if backpack_data table has the 'uuid' column
        JDBCsetUp.QueryResult backpackColCheck = JDBCsetUp.executeQuery(
                "SELECT COUNT(*) AS colCount FROM INFORMATION_SCHEMA.COLUMNS " +
                        "WHERE TABLE_SCHEMA = '" + dbName + "' " +
                        "AND TABLE_NAME = 'backpack_data' " +
                        "AND COLUMN_NAME = 'uuid';"
        );
        ResultSet rsBackpackCol = backpackColCheck.resultSet();
        if (rsBackpackCol.next() && rsBackpackCol.getInt("colCount") == 0) {
            LOGGER.info("Altering backpack_data table to add missing 'uuid' column.");
            // Add the missing column and set it as primary key.
            JDBCsetUp.executeUpdate("ALTER TABLE " + dbName + ".backpack_data ADD COLUMN uuid CHAR(36) NOT NULL", 1);
            JDBCsetUp.executeUpdate("ALTER TABLE " + dbName + ".backpack_data ADD PRIMARY KEY (uuid)", 1);
        }
        rsBackpackCol.close();

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
                LOGGER.info("Altering player_data table to modify 'advancements' column to MEDIUMBLOB.");
                JDBCsetUp.executeUpdate("ALTER TABLE " + dbName + ".player_data MODIFY COLUMN advancements MEDIUMBLOB", 1);
            }
        }
        rsAdvCol.close();
        // ----- END NEW BLOCK -----

        LOGGER.info("PlayerSync is ready!");
    }

}
