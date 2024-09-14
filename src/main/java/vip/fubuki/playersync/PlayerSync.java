package vip.fubuki.playersync;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.event.server.FMLServerStartingEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import vip.fubuki.playersync.config.JdbcConfig;
import vip.fubuki.playersync.sync.ChatSync;
import vip.fubuki.playersync.sync.VanillaSync;
import vip.fubuki.playersync.util.JDBCsetUp;

import java.sql.SQLException;

@Mod(PlayerSync.MODID)
public class PlayerSync
{
    public static final String MODID = "playersync";
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
    public void onServerStarting(FMLServerStartingEvent event) throws SQLException {
        JDBCsetUp.executeUpdate("CREATE DATABASE IF NOT EXISTS "+JdbcConfig.DATABASE_NAME.get(),1);

        JDBCsetUp.executeUpdate("CREATE TABLE IF NOT EXISTS player_data (uuid CHAR(36) NOT NULL," +
                "inventory MEDIUMBLOB,armor BLOB,advancements BLOB,enderchest MEDIUMBLOB,effects BLOB," +
                "xp int,food_level int,score int,health int,online boolean, last_server int, PRIMARY KEY (uuid));");
        JDBCsetUp.executeUpdate("CREATE TABLE IF NOT EXISTS chat (player CHAR(36) NOT NULL,message TEXT," +
                "timestamp BIGINT);");
        JDBCsetUp.executeUpdate("CREATE TABLE IF NOT EXISTS server_info (`id` INT NOT NULL,`enable` boolean NOT NULL,`last_update` BIGINT NOT NULL,PRIMARY KEY (`id`));");
        long current = System.currentTimeMillis();
        JDBCsetUp.executeUpdate("INSERT INTO server_info(id,enable,last_update) " +
                "VALUES(" + JdbcConfig.SERVER_ID.get() + ",true," + current + ") " +
                "ON DUPLICATE KEY UPDATE id= " + JdbcConfig.SERVER_ID.get() +",enable = 1," +
                "last_update=" + current + ";");
        JDBCsetUp.executeUpdate("UPDATE server_info SET enable= 1 WHERE id= "+ JdbcConfig.SERVER_ID.get());

        if(ModList.get().isLoaded("curios")) {
            JDBCsetUp.executeUpdate("CREATE TABLE IF NOT EXISTS curios (uuid CHAR(36) NOT NULL,curios_item BLOB, PRIMARY KEY (uuid))");
        }
    }

}
