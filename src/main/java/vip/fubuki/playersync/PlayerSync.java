package vip.fubuki.playersync;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import vip.fubuki.playersync.config.JdbcConfig;

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

}
