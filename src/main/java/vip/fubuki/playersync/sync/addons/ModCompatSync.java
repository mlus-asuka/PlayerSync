package vip.fubuki.playersync.sync.addons;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import vip.fubuki.playersync.PlayerSync;
import vip.fubuki.playersync.sync.VanillaSync;
import vip.fubuki.playersync.util.JDBCsetUp;
import vip.fubuki.playersync.util.LocalJsonUtil;
import vip.fubuki.playersync.util.Tables;

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

    // FIX PERF (C4): Cache reflection Method lookups for NeoForge AttachmentHolder.
    // Previously resolved on every snapshot/apply (35 players × auto-save = thousands of
    // reflective lookups / hour). Static-init once, reuse forever.
    private static final java.lang.reflect.Method SERIALIZE_ATTACHMENTS;
    private static final java.lang.reflect.Method DESERIALIZE_ATTACHMENTS;
    static {
        java.lang.reflect.Method ser = null, des = null;
        try {
            ser = net.neoforged.neoforge.attachment.AttachmentHolder.class
                    .getDeclaredMethod("serializeAttachments", net.minecraft.core.HolderLookup.Provider.class);
            ser.setAccessible(true);
            des = net.neoforged.neoforge.attachment.AttachmentHolder.class
                    .getDeclaredMethod("deserializeAttachments",
                            net.minecraft.core.HolderLookup.Provider.class,
                            net.minecraft.nbt.CompoundTag.class);
            des.setAccessible(true);
        } catch (NoSuchMethodException e) {
            PlayerSync.LOGGER.error("[PlayerSync] Could not cache AttachmentHolder reflection methods; NeoForge attachment sync will be disabled.", e);
        }
        SERIALIZE_ATTACHMENTS = ser;
        DESERIALIZE_ATTACHMENTS = des;
    }

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
                    "REPLACE INTO " + Tables.modPlayerData() + " (uuid, mod_id, data_value) VALUES (?, ?, ?)",
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
                    "SELECT data_value FROM " + Tables.modPlayerData() + " WHERE uuid=? AND mod_id=?",
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

    /**
     * Applies pre-read Accessories data to the player entity (NO DB access).
     * Used by doPlayerJoin to avoid DB reads on the main thread.
     */
    public static void applyAccessoriesFromData(Player player, String accessoriesData) {
        if (!ModList.get().isLoaded("accessories")) return;
        try {
            io.wispforest.accessories.api.AccessoriesCapability cap =
                    io.wispforest.accessories.api.AccessoriesCapability.get(player);
            if (cap == null) return;

            Map<String, io.wispforest.accessories.api.AccessoriesContainer> containers = cap.getContainers();

            // FIX ANTI-DUPLICATION: ALWAYS clear accessories slots first to wipe stale
            // data from Minecraft's .dat file, then only restore if DB has valid data.
            for (io.wispforest.accessories.api.AccessoriesContainer container : containers.values()) {
                var accessories = container.getAccessories();
                for (int i = 0; i < accessories.getContainerSize(); i++) {
                    accessories.setItem(i, ItemStack.EMPTY);
                }
            }

            if (accessoriesData == null || accessoriesData.length() <= 2) return;

            Map<String, String> storedMap = LocalJsonUtil.StringToMap(accessoriesData);
            if (storedMap.isEmpty()) return;

            for (Map.Entry<String, String> entry : storedMap.entrySet()) {
                String compositeKey = entry.getKey();
                int lastColon = compositeKey.lastIndexOf(':');
                if (lastColon < 0) continue;
                String slotType = compositeKey.substring(0, lastColon);
                int slotIndex;
                try { slotIndex = Integer.parseInt(compositeKey.substring(lastColon + 1)); }
                catch (NumberFormatException ex) { continue; }

                try {
                    ItemStack stack = VanillaSync.deserializeAndCreatePlaceholderIfNeeded(entry.getValue());
                    if (containers.containsKey(slotType)) {
                        var acc = containers.get(slotType).getAccessories();
                        if (slotIndex < acc.getContainerSize()) {
                            acc.setItem(slotIndex, stack);
                        }
                    }
                } catch (Exception e) {
                    PlayerSync.LOGGER.error("Error applying Accessories data for key {}", compositeKey, e);
                }
            }
            PlayerSync.LOGGER.info("Applied Accessories data for player {}", player.getUUID());
        } catch (Exception e) {
            PlayerSync.LOGGER.error("Error applying Accessories data for player {}", player.getUUID(), e);
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
                    "REPLACE INTO " + Tables.modPlayerData() + " (uuid, mod_id, data_value) VALUES (?, ?, ?)",
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
                    "SELECT data_value FROM " + Tables.modPlayerData() + " WHERE uuid=? AND mod_id=?",
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

    /**
     * Applies pre-read CosmeticArmor data to the player entity (NO DB access).
     */
    public static void applyCosmeticArmorFromData(Player player, String cosmeticArmorData) {
        if (!ModList.get().isLoaded("cosmeticarmorreworked")) return;
        try {
            lain.mods.cos.impl.inventory.InventoryCosArmor cosInv =
                    lain.mods.cos.impl.ModObjects.invMan.getCosArmorInventory(player.getUUID());
            if (cosInv == null) return;

            // FIX ANTI-DUPLICATION: ALWAYS clear cosmetic armor slots first to wipe stale
            // data from Minecraft's .dat file, then only restore if DB has valid data.
            for (int i = 0; i < cosInv.getContainerSize(); i++) {
                cosInv.setItem(i, ItemStack.EMPTY);
            }

            if (cosmeticArmorData == null || cosmeticArmorData.length() <= 2) return;

            Map<Integer, String> storedMap = LocalJsonUtil.StringToEntryMap(cosmeticArmorData);
            if (storedMap.isEmpty()) return;

            for (Map.Entry<Integer, String> entry : storedMap.entrySet()) {
                int slot = entry.getKey();
                try {
                    ItemStack stack = VanillaSync.deserializeAndCreatePlaceholderIfNeeded(entry.getValue());
                    if (slot < cosInv.getContainerSize()) {
                        cosInv.setItem(slot, stack);
                    }
                } catch (Exception e) {
                    PlayerSync.LOGGER.error("Error applying CosmeticArmor slot {}", slot, e);
                }
            }
            cosInv.setChanged();
            PlayerSync.LOGGER.info("Applied CosmeticArmor data for player {}", player.getUUID());
        } catch (Exception e) {
            PlayerSync.LOGGER.error("Error applying CosmeticArmor data for player {}", player.getUUID(), e);
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
            if (SERIALIZE_ATTACHMENTS == null) return;

            net.minecraft.nbt.CompoundTag attachments = (net.minecraft.nbt.CompoundTag)
                    SERIALIZE_ATTACHMENTS.invoke(player, serverPlayer.getServer().registryAccess());

            if (attachments != null && !attachments.isEmpty()) {
                String serialized = VanillaSync.serializeTagToBinaryBase64(attachments);
                JDBCsetUp.executePreparedUpdate(
                        "REPLACE INTO " + Tables.modPlayerData() + " (uuid, mod_id, data_value) VALUES (?, ?, ?)",
                        player.getUUID().toString(), "neoforge_attachments", serialized);
                PlayerSync.LOGGER.debug("Saved NeoForge attachments for player {} ({} keys)",
                        player.getUUID(), attachments.getAllKeys().size());
            }
        } catch (Exception e) {
            PlayerSync.LOGGER.error("Error saving NeoForge attachments for player {}", player.getUUID(), e);
        }
    }

    /**
     * Restores NeoForge player attachments from the database.
     * Uses reflection to call NeoForge's internal deserializeAttachments method,
     * which ensures the exact same deserialization path as a normal player load.
     *
     * FIX: The method signature is deserializeAttachments(HolderLookup.Provider, CompoundTag),
     * NOT deserializeAttachments(CompoundTag). The old code passed wrong parameters causing
     * silent failure - no NeoForge attachment data (SOL Onion, Ars Nouveau, etc.) was restored.
     */
    public static void restoreNeoForgeAttachments(Player player) {
        try {
            if (!(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)) return;
            if (DESERIALIZE_ATTACHMENTS == null) return;

            String serialized;
            try (JDBCsetUp.QueryResult qr = JDBCsetUp.executePreparedQuery(
                    "SELECT data_value FROM " + Tables.modPlayerData() + " WHERE uuid=? AND mod_id=?",
                    player.getUUID().toString(), "neoforge_attachments")) {
                ResultSet rs = qr.resultSet();
                if (!rs.next()) return;
                serialized = rs.getString("data_value");
            }

            if (serialized == null || !serialized.startsWith("BNBT:")) return;

            net.minecraft.nbt.CompoundTag attachments = VanillaSync.deserializeBinaryBase64Tag(serialized);
            if (attachments.isEmpty()) return;

            net.minecraft.nbt.CompoundTag wrapper = new net.minecraft.nbt.CompoundTag();
            wrapper.put("neoforge:attachments", attachments);

            DESERIALIZE_ATTACHMENTS.invoke(player, serverPlayer.getServer().registryAccess(), wrapper);

            PlayerSync.LOGGER.info("Restored NeoForge attachments for player {} ({} keys)",
                    player.getUUID(), attachments.getAllKeys().size());
        } catch (Exception e) {
            PlayerSync.LOGGER.error("Error restoring NeoForge attachments for player {}", player.getUUID(), e);
        }
    }

    /**
     * Applies pre-read NeoForge attachments data to the player entity (NO DB access).
     */
    public static void applyAttachmentsFromData(Player player, String serialized) {
        if (serialized == null || !serialized.startsWith("BNBT:")) return;
        if (DESERIALIZE_ATTACHMENTS == null) return;
        try {
            if (!(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)) return;

            net.minecraft.nbt.CompoundTag attachments = VanillaSync.deserializeBinaryBase64Tag(serialized);
            if (attachments.isEmpty()) return;

            net.minecraft.nbt.CompoundTag wrapper = new net.minecraft.nbt.CompoundTag();
            wrapper.put("neoforge:attachments", attachments);

            DESERIALIZE_ATTACHMENTS.invoke(player, serverPlayer.getServer().registryAccess(), wrapper);

            PlayerSync.LOGGER.info("Applied NeoForge attachments for player {} ({} keys)",
                    player.getUUID(), attachments.getAllKeys().size());
        } catch (Exception e) {
            PlayerSync.LOGGER.error("Error applying NeoForge attachments for player {}", player.getUUID(), e);
        }
    }

    // ============================
    // Snapshot methods (main thread - entity reads only, NO DB writes)
    // These are used by auto-save and SaveToFile to capture entity state on the
    // main thread, then the actual DB writes happen on a background thread.
    // ============================

    /**
     * Captures Accessories slot data on the main thread.
     * Returns serialized string or null if mod not loaded / no data.
     */
    public static String snapshotAccessories(Player player) {
        if (!ModList.get().isLoaded("accessories")) return null;
        try {
            io.wispforest.accessories.api.AccessoriesCapability cap =
                    io.wispforest.accessories.api.AccessoriesCapability.get(player);
            // FIX ANTI-LOSS (A2): cap==null means the capability isn't attached yet —
            // return null to SKIP write and preserve DB. Do NOT return "{}" here, as that
            // would wipe a legitimate accessories record.
            if (cap == null) return null;
            Map<String, String> flatMap = new HashMap<>();
            for (Map.Entry<String, io.wispforest.accessories.api.AccessoriesContainer> entry : cap.getContainers().entrySet()) {
                String slotType = entry.getKey();
                var accessories = entry.getValue().getAccessories();
                for (int i = 0; i < accessories.getContainerSize(); i++) {
                    ItemStack stack = accessories.getItem(i);
                    if (!stack.isEmpty()) {
                        flatMap.put(slotType + ":" + i, VanillaSync.getNbtForStorage(stack));
                    }
                }
            }
            // Cap read OK — "{}" is intentional for truly empty slots so apply clears stale .dat.
            return flatMap.toString();
        } catch (Exception e) {
            PlayerSync.LOGGER.error("Error snapshotting Accessories for player {}", player.getUUID(), e);
            return null;
        }
    }

    /**
     * Captures Cosmetic Armor slot data on the main thread.
     * Returns serialized string or null if mod not loaded / no data.
     */
    public static String snapshotCosmeticArmor(Player player) {
        if (!ModList.get().isLoaded("cosmeticarmorreworked")) return null;
        try {
            lain.mods.cos.impl.inventory.InventoryCosArmor cosInv =
                    lain.mods.cos.impl.ModObjects.invMan.getCosArmorInventory(player.getUUID());
            // FIX ANTI-LOSS (A2): null manager → cannot read → SKIP write, preserve DB.
            if (cosInv == null) return null;
            Map<Integer, String> flatMap = new HashMap<>();
            for (int i = 0; i < cosInv.getContainerSize(); i++) {
                ItemStack stack = cosInv.getItem(i);
                if (!stack.isEmpty()) {
                    flatMap.put(i, VanillaSync.getNbtForStorage(stack));
                }
            }
            // Read OK — "{}" for truly empty slots so apply clears stale .dat.
            return flatMap.toString();
        } catch (Exception e) {
            PlayerSync.LOGGER.error("Error snapshotting CosmeticArmor for player {}", player.getUUID(), e);
            return null;
        }
    }

    /**
     * Captures NeoForge attachment data on the main thread via reflection.
     * Returns BNBT-serialized string or null if no data.
     */
    public static String snapshotAttachments(Player player) {
        if (SERIALIZE_ATTACHMENTS == null) return null;
        try {
            if (!(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)) return null;
            net.minecraft.nbt.CompoundTag attachments = (net.minecraft.nbt.CompoundTag)
                    SERIALIZE_ATTACHMENTS.invoke(player, serverPlayer.getServer().registryAccess());
            if (attachments == null || attachments.isEmpty()) return null;
            return VanillaSync.serializeTagToBinaryBase64(attachments);
        } catch (Exception e) {
            PlayerSync.LOGGER.error("Error snapshotting NeoForge attachments for player {}", player.getUUID(), e);
            return null;
        }
    }

    /**
     * Writes pre-snapshotted mod data to the DB.
     * NO entity access — safe to call from a background thread.
     *
     * @param uuid            player UUID string
     * @param accessoriesData serialized Accessories slots (may be null → skipped)
     * @param cosmeticArmor   serialized Cosmetic Armor slots (may be null → skipped)
     * @param attachments     serialized NeoForge attachments (may be null → skipped)
     */
    /**
     * Writes pre-snapshotted mod data to the DB, guarded by last_server to prevent
     * stale servers from overwriting fresher data after a player switched servers.
     */
    public static void writeModSnapshot(String uuid, String accessoriesData, String cosmeticArmor, String attachments, int serverId) throws SQLException {
        // FIX ANTI-DUPLICATION: Only write if this server still owns the player.
        // Uses UPDATE + INSERT IGNORE pattern guarded by last_server subquery.
        if (accessoriesData != null) {
            writeGuardedModData(uuid, "accessories", accessoriesData, serverId);
        }
        if (cosmeticArmor != null) {
            writeGuardedModData(uuid, "cosmeticarmor", cosmeticArmor, serverId);
        }
        if (attachments != null) {
            writeGuardedModData(uuid, "neoforge_attachments", attachments, serverId);
        }
    }

    /** Backwards-compatible overload (no server guard — used by direct store methods). */
    public static void writeModSnapshot(String uuid, String accessoriesData, String cosmeticArmor, String attachments) throws SQLException {
        if (accessoriesData != null) {
            JDBCsetUp.executePreparedUpdate(
                    "REPLACE INTO " + Tables.modPlayerData() + " (uuid, mod_id, data_value) VALUES (?, ?, ?)",
                    uuid, "accessories", accessoriesData);
        }
        if (cosmeticArmor != null) {
            JDBCsetUp.executePreparedUpdate(
                    "REPLACE INTO " + Tables.modPlayerData() + " (uuid, mod_id, data_value) VALUES (?, ?, ?)",
                    uuid, "cosmeticarmor", cosmeticArmor);
        }
        if (attachments != null) {
            JDBCsetUp.executePreparedUpdate(
                    "REPLACE INTO " + Tables.modPlayerData() + " (uuid, mod_id, data_value) VALUES (?, ?, ?)",
                    uuid, "neoforge_attachments", attachments);
        }
    }

    private static void writeGuardedModData(String uuid, String modId, String data, int serverId) throws SQLException {
        // FIX: Handle legacy rows with last_server IS NULL (same pattern as writeSnapshotToDB)
        String serverGuard = "(last_server=? OR last_server IS NULL)";
        // Update existing row only if this server still owns the player
        JDBCsetUp.executePreparedUpdate(
                "UPDATE " + Tables.modPlayerData() + " SET data_value=? WHERE uuid=? AND mod_id=? AND EXISTS (SELECT 1 FROM " + Tables.playerData() + " WHERE uuid=? AND " + serverGuard + ")",
                data, uuid, modId, uuid, serverId);
        // Insert if row doesn't exist yet (first save)
        JDBCsetUp.executePreparedUpdate(
                "INSERT IGNORE INTO " + Tables.modPlayerData() + " (uuid, mod_id, data_value) SELECT ?, ?, ? FROM " + Tables.playerData() + " WHERE uuid=? AND " + serverGuard,
                uuid, modId, data, uuid, serverId);
    }

    // ============================
    // Convenience methods
    // ============================

    /**
     * Saves all mod-specific data for a player synchronously.
     * Called on logout and server shutdown (main thread — entity reads are safe here).
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
