package vip.fubuki.playersync;

import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import vip.fubuki.playersync.config.JdbcConfig;
import vip.fubuki.playersync.util.JDBCsetUp;

import java.sql.ResultSet;
import java.sql.SQLException;

@Mod(PlayerSync.MODID)
public class PlayerSync
{
    public static final String MODID = "playersync";
    public PlayerSync()
    {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, JdbcConfig.COMMON_CONFIG);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) throws SQLException {
        if (event.getPlayer() instanceof ServerPlayerEntity) {
            String player_uuid = event.getEntity().getUUID().toString();
            JDBCsetUp.QueryResult queryResult=JDBCsetUp.executeQuery("SELECT * FROM AstralSorcery WHERE player='" + player_uuid + "';");
            ResultSet resultSet=queryResult.getResultSet();
            if(!resultSet.next()){
                JDBCsetUp.executeUpdate("INSERT INTO AstralSorcery(player,tag) VALUES('"+player_uuid+"','{}');");
            }
            JDBCsetUp.QueryResult queryResult1=JDBCsetUp.executeQuery("SELECT * FROM FTB WHERE player='" + player_uuid + "';");
            ResultSet resultSet1=queryResult1.getResultSet();
            if(!resultSet1.next()){
                JDBCsetUp.executeUpdate("INSERT INTO FTB(player,tag) VALUES('"+player_uuid+"','{}');");
            }
        }
    }

}
