package vip.fubuki.playersync;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import vip.fubuki.playersync.config.JdbcConfig;
import vip.fubuki.playersync.util.JDBCsetUp;

import java.sql.SQLException;

@Mod(PlayerSync.MODID)
public class PlayerSync
{
    public static final String MODID = "playersync";
    private static final Logger LOGGER = LogUtils.getLogger();
    public PlayerSync()
    {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, JdbcConfig.COMMON_CONFIG);
        modEventBus.addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {}

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) throws SQLException, ClassNotFoundException, InstantiationException, IllegalAccessException {
        JDBCsetUp.executeUpdate("CREATE DATABASE IF NOT EXISTS "+JdbcConfig.DATABASE_NAME.get());
        JDBCsetUp.executeUpdate("USE "+JdbcConfig.DATABASE_NAME.get());
        JDBCsetUp.executeUpdate("CREATE TABLE IF NOT EXISTS player_data (uuid CHAR(36) NOT NULL,inventory BLOB,armor BLOB,advancements BLOB,enderchest BLOB,effects BLOB,xp int,food_level int,score int,health int,online boolean, PRIMARY KEY (uuid))");
        LOGGER.info("PlayerSync is ready!");
    }

}
