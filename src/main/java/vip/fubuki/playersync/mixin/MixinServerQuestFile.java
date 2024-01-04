package vip.fubuki.playersync.mixin;


import dev.ftb.mods.ftblibrary.snbt.SNBT;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;
import java.util.Iterator;

import static dev.ftb.mods.ftbquests.quest.ServerQuestFile.FTBQUESTS_DATA;

@Mixin(ServerQuestFile.class)
public class MixinServerQuestFile {

    @Shadow @Final public MinecraftServer server;

    @Inject(method ="saveNow",at = @At("RETURN"))
    private void saveNow(CallbackInfo ci){
        Path path = this.server.getWorldPath(FTBQUESTS_DATA);

    }

    @Inject(method="load",at = @At("HEAD"))
    private void load(CallbackInfo ci){
        Path path = this.server.getWorldPath(FTBQUESTS_DATA);
//        SNBT.write(path.resolve(data.uuid + ".snbt"), data.serializeNBT());

    }
}
