package vip.fubuki.playersync.mixin.accessor;

import dev.ftb.mods.ftbquests.quest.QuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.nio.file.Path;
import java.util.Collection;

@Mixin(value = QuestFile.class,remap = false)
public interface QuestFileAccessor {
    @Invoker
    void invokeWriteDataFull(Path folder);
    @Invoker
    Collection<TeamData> invokeGetAllData();

    @Invoker
    void invokeReadDataFull(Path folder);
    void invokeAddData(TeamData data, boolean override);
}
