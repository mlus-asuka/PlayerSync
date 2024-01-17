package vip.fubuki.playersync.mixin;

import hellfirepvp.astralsorcery.common.data.research.PlayerProgress;
import net.minecraft.nbt.CompoundNBT;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import vip.fubuki.playersync.util.JDBCsetUp;

import java.sql.PreparedStatement;
import java.sql.SQLException;

@Mixin(PlayerProgress.class)
public class MixinPlayerProgress{

    @ModifyArg(method = "store", at = @At(value = "INVOKE", target = "Lhellfirepvp/astralsorcery/common/data/research/PlayerPerkData;save(Lnet/minecraft/nbt/CompoundNBT;)V"))
    private CompoundNBT save(CompoundNBT tag) {
        String nbt = tag.toString();
        nbt.replace(",","|").replace("\"","^").replace("{","<").replace("}",">").replace("'","~");
        try {
            PreparedStatement preparedStatement= JDBCsetUp.getConnection().prepareStatement("UPDATE AstralSorcery SET tag=? WHERE player=?");
            preparedStatement.setString(2,tag.getString("UUID"));
            preparedStatement.setString(1,nbt);
            preparedStatement.executeUpdate();
        } catch (SQLException throwable) {
            throwable.printStackTrace();
        }
        return tag;
    }
}
