package vip.fubuki.playersync;

import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;

public class ModsSupport {
    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (ModList.get().isLoaded("curios")) {
           //TODO curios support
        }
        if(ModList.get().isLoaded("sophisticatedbackpacks")) {
            //TODO sophisticatedbackpacks support
        }
    }
}
