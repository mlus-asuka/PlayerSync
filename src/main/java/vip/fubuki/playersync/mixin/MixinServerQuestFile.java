package vip.fubuki.playersync.mixin;


import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;

@Mixin(ServerQuestFile.class)
public abstract class MixinServerQuestFile {

    @Shadow @Final public MinecraftServer server;

    @Shadow
    private boolean shouldSave;

    @Shadow private Path folder;
    @Shadow private boolean isLoading;

    @Shadow public abstract Path getFolder();

    @Inject(method ="saveNow",at = @At("RETURN"), cancellable = true)
    private void saveNow(CallbackInfo ci){
//        if (shouldSave) {
//            ((QuestFileAccessor) this).invokeWriteDataFull(this.getFolder());
//            shouldSave = false;
//        }
//
//        Path path = server.getWorldPath(FTBQUESTS_DATA);
//
//        for (TeamData data : ((QuestFileAccessor) this).invokeGetAllData()) {
//            if (data.shouldSave) {
//                SNBT.write(path.resolve(data.uuid + ".snbt"), data.serializeNBT());
//
//                String nbt = data.serializeNBT().toString();
//                nbt.replace(",", "|").replace("\"", "^").replace("{", "<").replace("}", ">").replace("'", "~");
//                try {
//                    PreparedStatement preparedStatement = JDBCsetUp.getConnection().prepareStatement("UPDATE FTB SET tag=? WHERE player=?");
//                    preparedStatement.setString(2, data.uuid.toString());
//                    preparedStatement.setString(1, nbt);
//                    preparedStatement.executeUpdate();
//                } catch (SQLException throwable) {
//                    throwable.printStackTrace();
//                }
//
//                data.shouldSave = false;
//            }
//        }
//
//        ci.cancel();
    }

    @Inject(method="load",at = @At("HEAD"))
    private void load(CallbackInfo ci){
//        folder = Platform.getConfigFolder().resolve("ftbquests/quests");
//
//        if (Files.exists(folder)) {
//            FTBQuests.LOGGER.info("Loading quests from " + folder);
//            isLoading = true;
//            ((QuestFileAccessor)this).invokeReadDataFull(folder);
//            isLoading = false;
//        }
//
//        Path path = server.getWorldPath(FTBQUESTS_DATA);
//
//        if (Files.exists(path)) {
//            try {
//                Files.list(path).filter(p -> p.getFileName().toString().contains("-")).forEach(path1 -> {
//                    SNBTCompoundTag nbt = SNBT.read(path1);
//
//                    if (nbt != null) {
//                        try {
//                            UUID uuid = UUIDTypeAdapter.fromString(nbt.getString("uuid"));
//                            TeamData data = new TeamData(uuid);
//                            data.file = (ServerQuestFile)((Object)this);
//                            this.addData(data, true);
//                            data.deserializeNBT(nbt);
//                        } catch (Exception ex) {
//                            ex.printStackTrace();
//                        }
//                    }
//                });
//            } catch (Exception ex) {
//                ex.printStackTrace();
//            }
//        }

    }
}
