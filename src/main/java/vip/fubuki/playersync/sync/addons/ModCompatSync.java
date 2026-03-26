package vip.fubuki.playersync.sync.addons;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import vip.fubuki.playersync.PlayerSync;
import vip.fubuki.playersync.sync.VanillaSync;
import vip.fubuki.playersync.util.JDBCsetUp;
import vip.fubuki.playersync.util.LocalJsonUtil;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * Mod compatibility handlers for syncing player data from:
 * - Accessories API (used by The Aether for pendant, cape, gloves, rings, etc.)
 * - Cosmetic Armor Reworked (4 cosmetic armor slots)
 * - Apotheosis (item DataComponents travel with inventory - automatic)
 */
public class ModCompatSync {

    // ============================
    // Accessories API (Aether slots)
    // ============================

    /**
     * Saves Accessories inventory (used by The Aether and other mods).
     * Works identically to Curios sync but uses the Accessories API.
     */
    public static void storeAccessories(Player player) {
        if (!ModList.get().isLoaded("accessories")) return;

        try {
            Map<String, String> flatMap = new HashMap<>();

            io.wispforest.accessories.api.AccessoriesCapability cap =
                    io.wispforest.accessories.api.AccessoriesCapability.get(player);
            if (cap == null) {
                PlayerSync.LOGGER.debug("No Accessories capability for player {}", player.getUUID());
                return;
            }

            Map<String, io.wispforest.accessories.api.AccessoriesContainer> containers = cap.getContainers();
            for (Map.Entry<String, io.wispforest.accessories.api.AccessoriesContainer> entry : containers.entrySet()) {
                String slotType = entry.getKey();
                io.wispforest.accessories.api.AccessoriesContainer container = entry.getValue();
                var accessories = container.getAccessories();
                for (int i = 0; i < accessories.getContainerSize(); i++) {
                    ItemStack stack = accessories.getItem(i);
                    if (!stack.isEmpty()) {
                        flatMap.put(slotType + ":" + i, VanillaSync.getNbtForStorage(stack));
                    }
                }
            }

            String serializedData = flatMap.toString();
            JDBCsetUp.executePreparedUpdate(
                    "REPLACE INTO mod_player_data (uuid, mod_id, data_value) VALUES (?, ?, ?)",
                    player.getUUID().toString(), "accessories", serializedData);
            PlayerSync.LOGGER.debug("Saved Accessories data for player {}", player.getUUID());

        } catch (Exception e) {
            PlayerSync.LOGGER.error("Error saving Accessories data for player {}", player.getUUID(), e);
        }
    }

    /**
     * Restores Accessories inventory for a player.
     * Same logic as Curios restore: validate data before clearing, then restore items.
     */
    public static void restoreAccessories(Player player) {
        if (!ModList.get().isLoaded("accessories")) return;

        try {
            io.wispforest.accessories.api.AccessoriesCapability cap =
                    io.wispforest.accessories.api.AccessoriesCapability.get(player);
            if (cap == null) {
                PlayerSync.LOGGER.debug("No Accessories capability for player {}", player.getUUID());
                return;
            }

            String accessoriesData;
            try (JDBCsetUp.QueryResult qr = JDBCsetUp.executePreparedQuery(
                    "SELECT data_value FROM mod_player_data WHERE uuid=? AND mod_id=?",
                    player.getUUID().toString(), "accessories")) {
                ResultSet rs = qr.resultSet();
                if (!rs.next()) {
                    // No data yet, perform initial save
                    storeAccessories(player);
                    return;
                }
                accessoriesData = rs.getString("data_value");
            }

            // Validate data before clearing
            if (accessoriesData == null || accessoriesData.length() <= 2) {
                PlayerSync.LOGGER.debug("Empty Accessories data for player {}, skipping restore", player.getUUID());
                return;
            }

            Map<String, String> storedMap = LocalJsonUtil.StringToMap(accessoriesData);
            if (storedMap.isEmpty()) return;

            Map<String, io.wispforest.accessories.api.AccessoriesContainer> containers = cap.getContainers();

            // Clear all Accessories slots ONLY after confirming valid data
            for (io.wispforest.accessories.api.AccessoriesContainer container : containers.values()) {
                var accessories = container.getAccessories();
                for (int i = 0; i < accessories.getContainerSize(); i++) {
                    accessories.setItem(i, ItemStack.EMPTY);
                }
            }

            // Restore items
            for (Map.Entry<String, String> entry : storedMap.entrySet()) {
                String compositeKey = entry.getKey();
                int lastColon = compositeKey.lastIndexOf(':');
                if (lastColon < 0) continue;

                String slotType = compositeKey.substring(0, lastColon);
                int slotIndex;
                try {
                    slotIndex = Integer.parseInt(compositeKey.substring(lastColon + 1));
                } catch (NumberFormatException ex) {
                    continue;
                }

                try {
                    ItemStack stack = VanillaSync.deserializeAndCreatePlaceholderIfNeeded(entry.getValue());
                    if (containers.containsKey(slotType)) {
                        var accessories = containers.get(slotType).getAccessories();
                        if (slotIndex < accessories.getContainerSize()) {
                            accessories.setItem(slotIndex, stack);
                        }
                    }
                } catch (CommandSyntaxException e) {
                    PlayerSync.LOGGER.error("Error deserializing Accessories data for key {}. Skipping.", compositeKey, e);
                } catch (Exception e) {
                    PlayerSync.LOGGER.error("Unexpected error restoring Accessories data for key {}. Skipping.", compositeKey, e);
                }
            }

            PlayerSync.LOGGER.info("Restored Accessories data for player {}", player.getUUID());
        } catch (Exception e) {
            PlayerSync.LOGGER.error("Error restoring Accessories data for player {}", player.getUUID(), e);
        }
    }

    // ============================
    // Cosmetic Armor Reworked
    // ============================

    /**
     * Saves Cosmetic Armor slots (4 cosmetic equipment slots: head, chest, legs, feet).
     */
    public static void storeCosmeticArmor(Player player) {
        if (!ModList.get().isLoaded("cosmeticarmorreworked")) return;

        try {
            Map<Integer, String> flatMap = new HashMap<>();

            lain.mods.cos.impl.inventory.InventoryCosArmor cosInv =
                    lain.mods.cos.impl.ModObjects.invMan.getCosArmorInventory(player.getUUID());
            if (cosInv == null) {
                PlayerSync.LOGGER.debug("No CosmeticArmor inventory for player {}", player.getUUID());
                return;
            }

            for (int i = 0; i < cosInv.getContainerSize(); i++) {
                ItemStack stack = cosInv.getItem(i);
                if (!stack.isEmpty()) {
                    flatMap.put(i, VanillaSync.getNbtForStorage(stack));
                }
            }

            String serializedData = flatMap.toString();
            JDBCsetUp.executePreparedUpdate(
                    "REPLACE INTO mod_player_data (uuid, mod_id, data_value) VALUES (?, ?, ?)",
                    player.getUUID().toString(), "cosmeticarmor", serializedData);
            PlayerSync.LOGGER.debug("Saved CosmeticArmor data for player {}", player.getUUID());

        } catch (Exception e) {
            PlayerSync.LOGGER.error("Error saving CosmeticArmor data for player {}", player.getUUID(), e);
        }
    }

    /**
     * Restores Cosmetic Armor slots for a player.
     */
    public static void restoreCosmeticArmor(Player player) {
        if (!ModList.get().isLoaded("cosmeticarmorreworked")) return;

        try {
            lain.mods.cos.impl.inventory.InventoryCosArmor cosInv =
                    lain.mods.cos.impl.ModObjects.invMan.getCosArmorInventory(player.getUUID());
            if (cosInv == null) {
                PlayerSync.LOGGER.debug("No CosmeticArmor inventory for player {}", player.getUUID());
                return;
            }

            String cosmeticData;
            try (JDBCsetUp.QueryResult qr = JDBCsetUp.executePreparedQuery(
                    "SELECT data_value FROM mod_player_data WHERE uuid=? AND mod_id=?",
                    player.getUUID().toString(), "cosmeticarmor")) {
                ResultSet rs = qr.resultSet();
                if (!rs.next()) {
                    // No data yet, perform initial save
                    storeCosmeticArmor(player);
                    return;
                }
                cosmeticData = rs.getString("data_value");
            }

            // Validate before clearing
            if (cosmeticData == null || cosmeticData.length() <= 2) {
                PlayerSync.LOGGER.debug("Empty CosmeticArmor data for player {}, skipping restore", player.getUUID());
                return;
            }

            Map<Integer, String> storedMap = LocalJsonUtil.StringToEntryMap(cosmeticData);
            if (storedMap.isEmpty()) return;

            // Clear cosmetic armor slots
            for (int i = 0; i < cosInv.getContainerSize(); i++) {
                cosInv.setItem(i, ItemStack.EMPTY);
            }

            // Restore items
            for (Map.Entry<Integer, String> entry : storedMap.entrySet()) {
                int slot = entry.getKey();
                try {
                    ItemStack stack = VanillaSync.deserializeAndCreatePlaceholderIfNeeded(entry.getValue());
                    if (slot < cosInv.getContainerSize()) {
                        cosInv.setItem(slot, stack);
                    }
                } catch (CommandSyntaxException e) {
                    PlayerSync.LOGGER.error("Error deserializing CosmeticArmor slot {}. Skipping.", slot, e);
                } catch (Exception e) {
                    PlayerSync.LOGGER.error("Unexpected error restoring CosmeticArmor slot {}. Skipping.", slot, e);
                }
            }

            // Mark the inventory as changed so the mod syncs to the client
            cosInv.setChanged();
            PlayerSync.LOGGER.info("Restored CosmeticArmor data for player {}", player.getUUID());

        } catch (Exception e) {
            PlayerSync.LOGGER.error("Error restoring CosmeticArmor data for player {}", player.getUUID(), e);
        }
    }

    // ============================
    // Generic NeoForge Attachment Sync
    // ============================

    /**
     * Saves ALL NeoForge player attachments to the database.
     * This covers per-player data from ALL mods, including:
     * - Ars Nouveau (mana, glyph knowledge)
     * - Iron's Spellbooks (mana, learned spells)
     * - Pehkui (player scale)
     * - Spice of Life: Onion (food diversity)
     * - Any other mod using NeoForge's attachment system
     *
     * Uses player.saveWithoutId() to extract the attachments tag from the
     * player's full serialized NBT, ensuring we capture ALL mod data.
     */
    public static void storeNeoForgeAttachments(Player player) {
        try {
            if (!(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)) return;

            net.minecraft.nbt.CompoundTag playerNbt = new net.minecraft.nbt.CompoundTag();
            serverPlayer.saveWithoutId(playerNbt);

            // NeoForge stores all attachment data under this key
            if (playerNbt.contains("neoforge:attachments", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                net.minecraft.nbt.CompoundTag attachments = playerNbt.getCompound("neoforge:attachments");
                if (!attachments.isEmpty()) {
                    String serialized = VanillaSync.serializeTagToBinaryBase64(attachments);
                    JDBCsetUp.executePreparedUpdate(
                            "REPLACE INTO mod_player_data (uuid, mod_id, data_value) VALUES (?, ?, ?)",
                            player.getUUID().toString(), "neoforge_attachments", serialized);
                    PlayerSync.LOGGER.debug("Saved NeoForge attachments for player {} ({} keys)",
                            player.getUUID(), attachments.getAllKeys().size());
                }
            }
        } catch (Exception e) {
            PlayerSync.LOGGER.error("Error saving NeoForge attachments for player {}", player.getUUID(), e);
        }
    }

    /**
     * Restores NeoForge player attachments from the database.
     * Uses reflection to call NeoForge's internal deserializeAttachments method,
     * which ensures the exact same deserialization path as a normal player load.
     */
    public static void restoreNeoForgeAttachments(Player player) {
        try {
            String serialized;
            try (JDBCsetUp.QueryResult qr = JDBCsetUp.executePreparedQuery(
                    "SELECT data_value FROM mod_player_data WHERE uuid=? AND mod_id=?",
                    player.getUUID().toString(), "neoforge_attachments")) {
                ResultSet rs = qr.resultSet();
                if (!rs.next()) return;
                serialized = rs.getString("data_value");
            }

            if (serialized == null || !serialized.startsWith("BNBT:")) return;

            net.minecraft.nbt.CompoundTag attachments = VanillaSync.deserializeBinaryBase64Tag(serialized);
            if (attachments.isEmpty()) return;

            // Create a wrapper CompoundTag with the attachments key
            net.minecraft.nbt.CompoundTag wrapper = new net.minecraft.nbt.CompoundTag();
            wrapper.put("neoforge:attachments", attachments);

            // Use reflection to call the package-private deserializeAttachments method
            // This ensures we use NeoForge's exact deserialization logic
            java.lang.reflect.Method deserializeMethod = net.neoforged.neoforge.attachment.AttachmentHolder.class
                    .getDeclaredMethod("deserializeAttachments", net.minecraft.nbt.CompoundTag.class);
            deserializeMethod.setAccessible(true);
            deserializeMethod.invoke(player, wrapper);

            PlayerSync.LOGGER.info("Restored NeoForge attachments for player {} ({} keys)",
                    player.getUUID(), attachments.getAllKeys().size());
        } catch (Exception e) {
            PlayerSync.LOGGER.error("Error restoring NeoForge attachments for player {}", player.getUUID(), e);
        }
    }

    // ============================
    // Convenience methods
    // ============================

    /**
     * Saves all mod-specific data for a player.
     * Called on logout and auto-save.
     */
    public static void storeAll(Player player) {
        storeAccessories(player);
        storeCosmeticArmor(player);
        storeNeoForgeAttachments(player);
    }

    /**
     * Restores all mod-specific data for a player.
     * Called on join.
     */
    public static void restoreAll(Player player) {
        restoreAccessories(player);
        restoreCosmeticArmor(player);
        restoreNeoForgeAttachments(player);
    }
}
