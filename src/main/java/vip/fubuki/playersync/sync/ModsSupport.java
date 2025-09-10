package vip.fubuki.playersync.sync;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fml.ModList;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;
import vip.fubuki.playersync.PlayerSync;
import vip.fubuki.playersync.util.JDBCsetUp;
import vip.fubuki.playersync.util.LocalJsonUtil;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;


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
                handlerOpt.ifPresent(handler -> handler.getCurios().forEach((slotType, stacksHandler) -> {
                    // Use the dynamic stack handler to clear slots.
                    IDynamicStackHandler dynStacks = stacksHandler.getStacks();
                    for (int i = 0; i < dynStacks.getSlots(); i++) {
                        dynStacks.setStackInSlot(i, ItemStack.EMPTY);
                    }
                }));

                // Restore each saved item.
                handlerOpt.ifPresent(handler -> {
                    handler.getCurios().clear();
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
                            ItemStack stack = VanillaSync.deserializeAndCreatePlaceholderIfNeeded(serialized);
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
        if(ModList.get().isLoaded("sophisticatedbackpacks")){
            // --- Begin Backpack Data Restore ---
            PlayerSync.LOGGER.info("Restoring backpack data for player " + player.getUUID());
            net.p3pp3rf1y.sophisticatedbackpacks.util.PlayerInventoryProvider.get().runOnBackpacks(player, (ItemStack backpackItem, String handler, String identifier, int slot) -> {
                backpackItem.getCapability(net.p3pp3rf1y.sophisticatedbackpacks.api.CapabilityBackpackWrapper.getCapabilityInstance())
                        .ifPresent(wrapper -> {
                            // Retrieve the contents UUID from the backpack's NBT using NBTHelper
                            Optional<UUID> uuidOpt = net.p3pp3rf1y.sophisticatedcore.util.NBTHelper.getUniqueId(wrapper.getBackpack(), "contentsUuid");
                            if (uuidOpt.isPresent()) {
                                UUID contentsUuid = uuidOpt.get();
                                try {
                                    JDBCsetUp.QueryResult qrBackpack = JDBCsetUp.executeQuery("SELECT backpack_nbt FROM backpack_data WHERE uuid='" + contentsUuid + "'");
                                    ResultSet rsBackpack = qrBackpack.resultSet();
                                    if (rsBackpack.next()) {
                                        String serialized = rsBackpack.getString("backpack_nbt");
                                        String nbtString = VanillaSync.deserializeString(serialized);
                                        CompoundTag backpackNbt = NbtUtils.snbtToStructure(nbtString);
                                        // Update BackpackStorage with the retrieved NBT
                                        net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackStorage.get().setBackpackContents(contentsUuid, backpackNbt);
                                        PlayerSync.LOGGER.info("Restored backpack data for UUID " + contentsUuid);
                                    }
                                    rsBackpack.close();
                                    qrBackpack.connection().close();
                                } catch (SQLException e) {
                                    PlayerSync.LOGGER.error("Error restoring backpack data for UUID " + contentsUuid, e);
                                } catch (CommandSyntaxException e) {
                                    throw new RuntimeException(e);
                                }
                            } else {
                                PlayerSync.LOGGER.warn("Backpack item in slot " + slot + " has no contentsUuid during restore");
                            }
                        });
                return false;
            });
            // --- End Backpack Data Restore ---
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

    public static void storeSophisticatedBackpacks(Player player) {
        PlayerSync.LOGGER.info("Storing backpack data for player " + player.getUUID());
        net.p3pp3rf1y.sophisticatedbackpacks.util.PlayerInventoryProvider.get().runOnBackpacks(player, (ItemStack backpackItem, String handler, String identifier, int slot) -> {
            backpackItem.getCapability(net.p3pp3rf1y.sophisticatedbackpacks.api.CapabilityBackpackWrapper.getCapabilityInstance())
                    .ifPresent(wrapper -> {
                        // Retrieve the contents UUID from the backpack's NBT using NBTHelper
                        Optional<UUID> uuidOpt = net.p3pp3rf1y.sophisticatedcore.util.NBTHelper.getUniqueId(wrapper.getBackpack(), "contentsUuid");
                        if (uuidOpt.isPresent()) {
                            UUID contentsUuid = uuidOpt.get();
                            // Get internal backpack data from BackpackStorage (creates it if missing)
                            CompoundTag backpackNbt = net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackStorage.get().getOrCreateBackpackContents(contentsUuid);
                            String serialized = VanillaSync.serialize(backpackNbt.toString());
                            try {
                                // Use REPLACE INTO so existing records are updated
                                JDBCsetUp.executeUpdate("REPLACE INTO backpack_data (uuid, backpack_nbt) VALUES ('" + contentsUuid + "', '" + serialized + "')");
                                PlayerSync.LOGGER.info("Saved backpack data for UUID " + contentsUuid);
                            } catch (SQLException e) {
                                PlayerSync.LOGGER.error("Error saving backpack data for UUID " + contentsUuid, e);
                            }
                        } else {
                            PlayerSync.LOGGER.warn("Backpack item in slot " + slot + " has no contentsUuid");
                        }
                    });
            return false; // Continue processing all backpack items.
        });
    }

}
