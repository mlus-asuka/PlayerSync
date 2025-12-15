package vip.fubuki.playersync.mixin.cobblemon.accessor;

import com.cobblemon.mod.common.api.storage.player.InstancedPlayerData;
import com.cobblemon.mod.common.api.storage.player.adapter.DexDataNbtBackend;
import com.mojang.serialization.Codec;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(DexDataNbtBackend.class)
public interface NbtBackedPlayerDataAccessor {
    @Accessor("codec")
    Codec<InstancedPlayerData> getCodec();

}
