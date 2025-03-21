package vip.fubuki.playersync.sync;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fml.ModList;
import vip.fubuki.playersync.util.JDBCsetUp;
import vip.fubuki.playersync.util.LocalJsonUtil;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;

public class ModsSupport {

    /**
     * Restores the Curios inventory for a player.
     * The saved data is stored as a flat map with composite keys ("slotType:index").
     */
    public void onPlayerJoin(net.minecraft.world.entity.player.Player player) throws SQLException {
        if (ModList.get().isLoaded("curios")) {
            // Obtain the handler from the API.
            LazyOptional<ICuriosItemHandler> handlerOpt = CuriosApi.getCuriosInventory(player);
            JDBCsetUp.QueryResult qr = JDBCsetUp.executeQuery("SELECT curios_item FROM curios WHERE uuid = '" + player.getUUID() + "'");
            ResultSet rs = qr.resultSet();
            if (rs.next()) {
                String curiosData = rs.getString("curios_item");
                if (curiosData.length() <= 2) {
                    rs.close();
                    qr.connection().close();
                    return;
                }
                // Parse the stored data (assumes a simple Map.toString() format: "{key=value, key2=value2, ...}")
                Map<String, String> storedMap = LocalJsonUtil.StringToMap(curiosData);

                // Clear current Curios slots to avoid conflicts.
                handlerOpt.ifPresent(handler -> {
                    handler.getCurios().forEach((slotType, stacksHandler) -> {
                        // Use the dynamic stack handler to clear slots.
                        IDynamicStackHandler dynStacks = stacksHandler.getStacks();
                        for (int i = 0; i < dynStacks.getSlots(); i++) {
                            dynStacks.setStackInSlot(i, ItemStack.EMPTY);
                        }
                    });
                });

                // Restore each saved item.
                handlerOpt.ifPresent(handler -> {
                    for (Map.Entry<String, String> entry : storedMap.entrySet()) {
                        String compositeKey = entry.getKey(); // Expected format: "slotType:index"
                        String[] parts = compositeKey.split(":");
                        if (parts.length != 2) {
                            continue;
                        }
                        String slotType = parts[0];
                        int slotIndex;
                        try {
                            slotIndex = Integer.parseInt(parts[1]);
                        } catch (NumberFormatException ex) {
                            continue;
                        }
                        String serialized = entry.getValue();
                        try {
                            String nbtString = VanillaSync.deserializeString(serialized);
                            CompoundTag tag = NbtUtils.snbtToStructure(nbtString);
                            ItemStack stack = ItemStack.of(tag);
                            if (handler.getCurios().containsKey(slotType)) {
                                ICurioStacksHandler stacksHandler = handler.getCurios().get(slotType);
                                IDynamicStackHandler dynStacks = stacksHandler.getStacks();
                                if (slotIndex < dynStacks.getSlots()) {
                                    dynStacks.setStackInSlot(slotIndex, stack);
                                }
                            }
                        } catch (CommandSyntaxException e) {
                            throw new RuntimeException("Error deserializing Curio data for key " + compositeKey, e);
                        }
                    }
                });
                rs.close();
                qr.connection().close();
            } else {
                // No stored data; perform an initial save.
                StoreCurios(player, true);
            }
        }
    }

    /**
     * Saves the current Curios inventory for a player.
     * It builds a flat map keyed by "slotType:index" using the dynamic stack handler.
     */
    public void onPlayerLeave(net.minecraft.world.entity.player.Player player) throws SQLException {
        if (ModList.get().isLoaded("curios")) {
            StoreCurios(player, false);
        }
    }

    public void StoreCurios(net.minecraft.world.entity.player.Player player, boolean init) throws SQLException {
        LazyOptional<ICuriosItemHandler> handlerOpt = CuriosApi.getCuriosInventory(player);
        Map<String, String> flatMap = new HashMap<>();

        handlerOpt.ifPresent(handler -> {
            // Iterate over each slot type.
            handler.getCurios().forEach((slotType, stacksHandler) -> {
                IDynamicStackHandler dynStacks = stacksHandler.getStacks();
                for (int i = 0; i < dynStacks.getSlots(); i++) {
                    ItemStack stack = dynStacks.getStackInSlot(i);
                    if (!stack.isEmpty()) {
                        String serialized = VanillaSync.serialize(stack.serializeNBT().toString());
                        flatMap.put(slotType + ":" + i, serialized);
                    }
                }
            });
        });

        String serializedData = flatMap.toString();
        if (init) {
            JDBCsetUp.executeUpdate("INSERT INTO curios (uuid,curios_item) VALUES ('" + player.getUUID() + "', '" + serializedData + "')");
        } else {
            JDBCsetUp.executeUpdate("UPDATE curios SET curios_item = '" + serializedData + "' WHERE uuid = '" + player.getUUID() + "'");
        }
    }
}
