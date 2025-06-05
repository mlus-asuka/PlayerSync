package vip.fubuki.playersync;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import vip.fubuki.playersync.sync.chat.ChatSyncClient;

@Mod.EventBusSubscriber()
public class CommandInit {

    @SubscribeEvent
    public static void registerCommand(RegisterCommandsEvent event){
        CommandDispatcher<CommandSourceStack> dispatcher=event.getDispatcher();
        dispatcher.register(Commands.literal("playersync")
                .requires(cs->cs.hasPermission(2))
                .then(Commands.literal("reconnect")
                        .executes(context -> {
                            new ChatSyncClient().run();
//                                  context.getSource().sendSuccess(()->MutableComponent.create(new TranslatableContents("playersync.command.reconnect")),true);
                                    return 0;
                                }
                        ))
        );
    }
}
