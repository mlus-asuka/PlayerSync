package vip.fubuki.playersync.sync;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.JsonToNBT;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.items.IItemHandlerModifiable;
import vip.fubuki.playersync.util.JDBCsetUp;
import vip.fubuki.playersync.util.LocalJsonUtil;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;


public class ModsSupport {

    public void onPlayerJoin(PlayerEntity player) throws SQLException {
        if (ModList.get().isLoaded("curios")) {
            /*
              Curios Support
             */
            LazyOptional<IItemHandlerModifiable> itemHandler = top.theillusivec4.curios.api.CuriosApi.getCuriosHelper().getEquippedCurios(player);
            JDBCsetUp.QueryResult queryResult=JDBCsetUp.executeQuery("SELECT curios_item FROM curios WHERE uuid = '"+player.getUUID()+"';");
            ResultSet resultSet = queryResult.getResultSet();
            if(resultSet.next()) {
                String curios_data=resultSet.getString("curios_item");
                if(curios_data.length()>2) {
                    Map<Integer, String> curios = LocalJsonUtil.StringToEntryMap(curios_data);
                    itemHandler.ifPresent(handler -> {
                        for (int i = 0; i < handler.getSlots(); i++) {
                            try {
                                if (curios.get(i) == null) continue;
                                handler.setStackInSlot(i, ItemStack.of(JsonToNBT.parseTag(curios.get(i).replace("|", ","))));
                            } catch (CommandSyntaxException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    });
                }
                    resultSet.close();
                    queryResult.getConnection().close();
            }else{
                StoreCurios(player,true);
            }
        }
    }

    public void onPlayerLeave(PlayerEntity player) throws SQLException {
        if (ModList.get().isLoaded("curios")) {
           StoreCurios(player, false);
        }
    }

    public void StoreCurios(PlayerEntity player,boolean init) throws SQLException {
        LazyOptional<IItemHandlerModifiable> itemHandler = top.theillusivec4.curios.api.CuriosApi.getCuriosHelper().getEquippedCurios(player);
        Map<Integer, String> curios = new HashMap<>();
        itemHandler.ifPresent(handler -> {
            for (int i = 0; i < handler.getSlots(); i++) {
                if (!handler.getStackInSlot(i).isEmpty()) {
                    String sNBT= handler.getStackInSlot(i).serializeNBT().toString().replace(",", "|");
                    curios.put(i, sNBT);
                }
            }
        });
        if(init) {
            JDBCsetUp.executeUpdate("INSERT INTO curios (uuid,curios_item) VALUES ('"+player.getUUID()+"','"+ curios+"')");
        } else {
            JDBCsetUp.executeUpdate("UPDATE curios SET curios_item = '"+ curios+"' WHERE uuid = '"+player.getUUID()+"'");
        }
    }
}
