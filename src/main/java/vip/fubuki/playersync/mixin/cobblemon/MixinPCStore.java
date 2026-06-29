package vip.fubuki.playersync.mixin.cobblemon;

import com.cobblemon.mod.common.api.storage.pc.PCStore;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import vip.fubuki.playersync.util.JDBCsetUp;
import vip.fubuki.playersync.util.LocalJsonUtil;
import vip.fubuki.playersync.util.Tables;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

@Mixin(PCStore.class)
public class MixinPCStore {
    @Final
    @Shadow
    private UUID uuid;

    @Inject(method = "saveToNBT",at = @At("TAIL"))
    private void saveToNBT$playerSync(CompoundTag nbt, RegistryAccess registryAccess, CallbackInfoReturnable<CompoundTag> cir) {
        String serializedData = nbt.toString();
        try {
            JDBCsetUp.executePreparedUpdate(
                    "INSERT INTO " + Tables.cobblemon() + " (uuid, pc) VALUES (?, ?) " +
                            "ON DUPLICATE KEY UPDATE pc = VALUES(pc)",
                    this.uuid.toString(), serializedData);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @ModifyVariable(method = "loadFromNBT", at = @At("HEAD"), argsOnly = true, name = "arg1")
    private CompoundTag loadFromNBT$playerSync(CompoundTag value) {
        CompoundTag loadedNbt = value;
        try (JDBCsetUp.QueryResult qr = JDBCsetUp.executePreparedQuery(
                "SELECT pc FROM " + Tables.cobblemon() + " WHERE uuid = ?", this.uuid.toString())) {
            ResultSet rs = qr.resultSet();
            if (rs.next()) {
                String serializedData = rs.getString("pc");
                loadedNbt = TagParser.parseTag(LocalJsonUtil.cleanSnbt(serializedData));
            }
        } catch (SQLException | CommandSyntaxException e) {
            throw new RuntimeException(e);
        }
        return loadedNbt;
    }
}
