package vip.fubuki.playersync;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber()
public class CommandInit {

    @SubscribeEvent
    public static void registerCommand(RegisterCommandsEvent event){
        CommandDispatcher<CommandSourceStack> dispatcher=event.getDispatcher();
        dispatcher.register(Commands.literal("playersync")
                .requires(cs->cs.hasPermission(2))
                .then(Commands.literal("reconnect")
                        .executes(context -> {
//                                  context.getSource().sendSuccess(()->MutableComponent.create(new TranslatableContents("playersync.command.reconnect")),true);
                                    return 0;
                                }
                        ))
        );
    }
}
