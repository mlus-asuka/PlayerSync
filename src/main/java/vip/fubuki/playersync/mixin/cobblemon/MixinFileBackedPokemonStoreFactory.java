package vip.fubuki.playersync.mixin.cobblemon;

import com.cobblemon.mod.common.api.storage.PokemonStore;
import com.cobblemon.mod.common.api.storage.factory.FileBackedPokemonStoreFactory;
import com.cobblemon.mod.common.api.storage.party.PartyStore;
import com.cobblemon.mod.common.api.storage.pc.PCStore;
import kotlin.jvm.functions.Function1;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import vip.fubuki.playersync.util.JDBCsetUp;

import java.sql.ResultSet;
import java.util.UUID;

@Mixin(FileBackedPokemonStoreFactory.class)
public class MixinFileBackedPokemonStoreFactory {
    @Unique
    RegistryAccess playerSync$registryAccess;

    @Inject(method = "getStore", at = @At("HEAD"))
    private <T extends PokemonStore<?>> void getStore$playerSync(Class<T> storeClass, UUID uuid, RegistryAccess registryAccess, Function1<? super UUID, ? extends T> constructor, CallbackInfoReturnable<T> cir){
        this.playerSync$registryAccess = registryAccess;
    }

    @Redirect(method = "getStore", at = @At(value = "INVOKE", target = "Lcom/cobblemon/mod/common/api/storage/PokemonStore;initialize()V"))
    private void getStore$playerSync(PokemonStore<?> instance) {

        String column;
        if(instance instanceof PCStore){
            column = "pc";
        } else if(instance instanceof PartyStore){
            column = "inv";
        }else {
            instance.initialize();
            return;
        }

        String sql = "SELECT " + column + " FROM cobblemon WHERE uuid = '" + instance.getUuid() + "'";

        try {
            JDBCsetUp.QueryResult qr = vip.fubuki.playersync.util.JDBCsetUp.executeQuery(sql);
            ResultSet rs = qr.resultSet();
            if (rs.next() && rs.getString(column) != null) {
                CompoundTag compoundTag = new CompoundTag();
                instance.loadFromNBT(compoundTag, playerSync$registryAccess);
            }

            rs.close();
            qr.close();
        } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
        }

        instance.initialize();
    }
}
