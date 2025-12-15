package vip.fubuki.playersync.mixin.cobblemon.accessor;

import com.cobblemon.mod.common.api.storage.player.PlayerInstancedDataStoreType;
import com.cobblemon.mod.common.api.storage.player.adapter.FileBasedPlayerDataStoreBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(FileBasedPlayerDataStoreBackend.class)
public interface FileBasedPlayerDataStoreBackendAccessor {
    @Accessor
    PlayerInstancedDataStoreType getType();
}
