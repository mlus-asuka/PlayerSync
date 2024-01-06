package vip.fubuki.playersync.mixin;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import hellfirepvp.astralsorcery.common.data.research.PlayerProgress;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.JsonToNBT;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import vip.fubuki.playersync.util.JDBCsetUp;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

@Mixin(PlayerProgress.class)
public abstract class MixinPlayerProgress {
    @Shadow public abstract void load(CompoundNBT compound);

    @ModifyArg(method = "store", at = @At(value = "INVOKE", target = "Lhellfirepvp/astralsorcery/common/data/research/PlayerPerkData;save(Lnet/minecraft/nbt/CompoundNBT;)V"))
    private CompoundNBT save(CompoundNBT tag) {
        tag.putBoolean("PlayerSync",false);
        String nbt = tag.toString().replace(",","|").replace("\"","^").replace("{","<").replace("}",">").replace("'","~");
        try {
            PreparedStatement preparedStatement= JDBCsetUp.getConnection().prepareStatement("INSERT INTO AstralSorcery(player,tag) VALUES(?,?) ON DUPLICATE KEY UPDATE player=?");
            preparedStatement.setString(1,tag.getString("UUID"));
            preparedStatement.setString(2,nbt);
            preparedStatement.setString(3,tag.getString("UUID"));
            preparedStatement.executeUpdate();
        } catch (SQLException throwable) {
            throwable.printStackTrace();
        }
        return tag;
    }

    @Inject(method = "load",at=@At(value="HEAD"), cancellable = true)
    private void load(CompoundNBT compound, CallbackInfo ci){
        if(compound.get("PlayerSync")==null || !compound.getBoolean("PlayerSync")){
            try {
                ResultSet result= JDBCsetUp.executeQuery("SELECT * FROM AstralSorcery WHERE player='" + compound.getString("UUID") + "';").getResultSet();
                if(result.next()){
                    String nbt = result.getString("tag").replace("|",",").replace("^","\"").replace("<","{").replace(">","}").replace("~", "'");
                    compound=JsonToNBT.parseTag(nbt);
                    compound.putBoolean("PlayerSync",true);
                    load(compound);
                }

            }catch (SQLException e){
                e.printStackTrace();
            } catch (CommandSyntaxException e) {
                throw new RuntimeException(e);
            }

            ci.cancel();
        }

    }
}
