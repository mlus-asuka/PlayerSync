package vip.fubuki.playersync;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import vip.fubuki.playersync.config.JdbcConfig;
import vip.fubuki.playersync.sync.ChatSync;
import vip.fubuki.playersync.sync.VanillaSync;
import vip.fubuki.playersync.util.JDBCsetUp;

import java.sql.SQLException;

@Mod(PlayerSync.MODID)
public class PlayerSync
{
    public static final String MODID = "playersync";
    public static final Logger LOGGER = LogUtils.getLogger();
    public PlayerSync()
    {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, JdbcConfig.COMMON_CONFIG);
        modEventBus.addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        VanillaSync.register();
        if(JdbcConfig.SYNC_CHAT.get()){
            ChatSync.register();
        }
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) throws SQLException {
        JDBCsetUp.executeUpdate("CREATE DATABASE IF NOT EXISTS `playersync`",1);

        JDBCsetUp.executeUpdate("""
                CREATE TABLE IF NOT EXISTS `player_data` (
                  `uuid` char(36) NOT NULL,
                  `inventory` mediumblob,
                  `armor` blob,
                  `advancements` blob,
                  `enderchest` mediumblob,
                  `effects` blob,
                  `xp` int DEFAULT NULL,
                  `food_level` int DEFAULT NULL,
                  `score` int DEFAULT NULL,
                  `health` int DEFAULT NULL,
                  `online` tinyint(1) DEFAULT NULL,
                  `last_server` int DEFAULT NULL,
                  PRIMARY KEY (`uuid`)
                );""");
        JDBCsetUp.executeUpdate("CREATE TABLE IF NOT EXISTS chat (player CHAR(36) NOT NULL,message TEXT," +
                "timestamp BIGINT)");
        JDBCsetUp.executeUpdate("""
                CREATE TABLE IF NOT EXISTS server_info (
                  `id` INT NOT NULL,
                  `enable` boolean NOT NULL,
                  `last_update` BIGINT NOT NULL,
                  PRIMARY KEY (`id`));""");
        long current = System.currentTimeMillis();
        JDBCsetUp.executeUpdate("INSERT INTO server_info(id,enable,last_update) " +
                "VALUES(" + JdbcConfig.SERVER_ID.get() + ",true," + current + ") " +
                "ON DUPLICATE KEY UPDATE id= " + JdbcConfig.SERVER_ID.get() +",enable = 1," +
                "last_update=" + current + ";");

        if(ModList.get().isLoaded("curios")) {
            JDBCsetUp.executeUpdate("CREATE TABLE IF NOT EXISTS curios (uuid CHAR(36) NOT NULL,curios_item BLOB, PRIMARY KEY (uuid))");
        }
        LOGGER.info("PlayerSync is ready!");
    }

}
