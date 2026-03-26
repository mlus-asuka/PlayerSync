package vip.fubuki.playersync.sync.addons;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.fml.ModList;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;
import vip.fubuki.playersync.PlayerSync;
import vip.fubuki.playersync.sync.VanillaSync;
import vip.fubuki.playersync.util.JDBCsetUp;
import vip.fubuki.playersync.util.LocalJsonUtil;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;


public class ModsSupport {
    public void doBackPackRestore(Player player) {
        if (ModList.get().isLoaded("sophisticatedbackpacks")) {
            PlayerSync.LOGGER.info("Restoring backpack data for player {}", player.getUUID());
            net.p3pp3rf1y.sophisticatedbackpacks.util.PlayerInventoryProvider.get().runOnBackpacks(player, (ItemStack backpackItem, String handler, String identifier, int slot) -> {
                net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.IBackpackWrapper backpackWrapper = net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper
                        .fromStack(backpackItem);

                Optional<UUID> uuidOpt = backpackWrapper.getContentsUuid();
                if (uuidOpt.isPresent()) {
                    UUID contentsUuid = uuidOpt.get();
                    restoreStorageContents(contentsUuid, (nbt) -> {
                        net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackStorage.get().setBackpackContents(contentsUuid, nbt);
                        PlayerSync.LOGGER.info("Restored backpack data for UUID {}", contentsUuid);
                    });
                } else {
                    PlayerSync.LOGGER.warn("Backpack item in slot {} has no contentsUuid during restore", slot);
                }
                return false;
            });
        }
    }

    /**
     * Generic method to restore storage contents from DB for a given UUID.
     * Used for both Sophisticated Backpacks and Sophisticated Storage items.
     */
    private static void restoreStorageContents(UUID contentsUuid, StorageRestoreCallback callback) {
        try (JDBCsetUp.QueryResult qr = JDBCsetUp.executePreparedQuery(
                "SELECT backpack_nbt FROM backpack_data WHERE uuid=?", contentsUuid.toString())) {
            ResultSet rs = qr.resultSet();
            if (rs.next()) {
                String serialized = rs.getString("backpack_nbt");
                CompoundTag nbt;
                if (serialized.startsWith("BNBT:")) {
                    nbt = VanillaSync.deserializeBinaryBase64Tag(serialized);
                } else {
                    String nbtString = VanillaSync.deserializeString(serialized);
                    try {
                        nbt = TagParser.parseTag(nbtString);
                    } catch (CommandSyntaxException ex) {
                        PlayerSync.LOGGER.warn("TagParser failed for storage UUID {}, trying fallback", contentsUuid);
                        nbt = net.minecraft.nbt.NbtUtils.snbtToStructure(nbtString);
                    }
                }
                callback.restore(nbt);
            }
        } catch (SQLException e) {
            PlayerSync.LOGGER.error("Error restoring storage data for UUID {}", contentsUuid, e);
        } catch (CommandSyntaxException e) {
            PlayerSync.LOGGER.error("Error parsing storage NBT for UUID {}. Skipping.", contentsUuid, e);
        } catch (IOException e) {
            PlayerSync.LOGGER.error("Error reading binary storage NBT for UUID {}. Skipping.", contentsUuid, e);
        }
    }

    @FunctionalInterface
    private interface StorageRestoreCallback {
        void restore(CompoundTag nbt);
    }

    /**
     * Generic method to save storage contents to DB for a given UUID.
     * Used for both Sophisticated Backpacks and Sophisticated Storage items.
     */
    private static void saveStorageContents(UUID contentsUuid, CompoundTag nbt) {
        String serialized = VanillaSync.serializeTagToBinaryBase64(nbt);
        try {
            JDBCsetUp.executePreparedUpdate(
                    "REPLACE INTO backpack_data (uuid, backpack_nbt) VALUES (?, ?)",
                    contentsUuid.toString(), serialized);
        } catch (SQLException e) {
            PlayerSync.LOGGER.error("Error saving storage data for UUID {}", contentsUuid, e);
        }
    }

    /**
     * Restores the Curios inventory for a player.
     * FIX: Slots are now cleared AFTER validating that data exists, preventing
     * curios from being wiped when DB contains empty/minimal data.
     */
    public void doCuriosRestore(Player player) throws SQLException {
        if (!ModList.get().isLoaded("curios")) return;

        Optional<ICuriosItemHandler> handlerOpt = CuriosApi.getCuriosInventory(player);
        if (handlerOpt.isEmpty()) {
            PlayerSync.LOGGER.warn("Could not get Curios handler for player {}", player.getUUID());
            return;
        }

        String curiosData;
        try (JDBCsetUp.QueryResult qr = JDBCsetUp.executePreparedQuery(
                "SELECT curios_item FROM curios WHERE uuid=?", player.getUUID().toString())) {
            ResultSet rs = qr.resultSet();
            if (!rs.next()) {
                // No stored data; perform an initial save.
                StoreCurios(player, true);
                return;
            }
            curiosData = rs.getString("curios_item");
        }

        // FIX: Check if data is valid BEFORE clearing slots
        if (curiosData == null || curiosData.length() <= 2) {
            PlayerSync.LOGGER.debug("Empty curios data for player {}, skipping restore", player.getUUID());
            return;
        }

        Map<String, String> storedMap = LocalJsonUtil.StringToMap(curiosData);
        if (storedMap.isEmpty()) {
            PlayerSync.LOGGER.debug("No curios entries for player {}, skipping restore", player.getUUID());
            return;
        }

        ICuriosItemHandler handler = handlerOpt.get();

        // Clear current Curios slots ONLY after confirming valid data exists
        handler.getCurios().forEach((slotType, stacksHandler) -> {
            IDynamicStackHandler dynStacks = stacksHandler.getStacks();
            for (int i = 0; i < dynStacks.getSlots(); i++) {
                dynStacks.setStackInSlot(i, ItemStack.EMPTY);
            }
        });

        // Restore each saved item
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
                PlayerSync.LOGGER.error("Error deserializing Curio data for key {}. Skipping.", compositeKey, e);
            } catch (Exception e) {
                PlayerSync.LOGGER.error("Unexpected error restoring Curio data for key {}. Skipping.", compositeKey, e);
            }
        }
    }

    /**
     * Saves the current Curios inventory for a player (normal case - player alive).
     */
    public void onPlayerLeave(Player player) throws SQLException {
        if (ModList.get().isLoaded("curios")) {
            StoreCurios(player, false);
        }
    }

    /**
     * FIX: Saves curios from cache if player is dead/dying, or from API if alive.
     * When a player dies, the Curios API may return empty data. The CuriosCache
     * stores a snapshot taken at death time, so we use that instead.
     */
    public void saveCuriosFromCacheOrApi(Player player) throws SQLException {
        if (!ModList.get().isLoaded("curios")) return;

        UUID playerUuid = player.getUUID();
        CuriosCache.CuriosCacheEntry cached = CuriosCache.curiosCache.get(playerUuid);

        if (cached != null && !cached.isExpired()) {
            // Use cached data from death event
            PlayerSync.LOGGER.info("Using cached curios data for dead player {}", playerUuid);
            JDBCsetUp.executePreparedUpdate(
                    "REPLACE INTO curios (uuid, curios_item) VALUES (?, ?)",
                    playerUuid.toString(), cached.serializedData);
            CuriosCache.curiosCache.remove(playerUuid);
        } else {
            // Fallback: try to read from API (may be empty for dead players)
            StoreCurios(player, false);
        }
    }

    public void StoreCurios(Player player, boolean init) throws SQLException {
        if (!ModList.get().isLoaded("curios")) return;

        Optional<ICuriosItemHandler> handlerOpt = CuriosApi.getCuriosInventory(player);
        Map<String, String> flatMap = new HashMap<>();

        handlerOpt.ifPresent(handler -> {
            handler.getCurios().forEach((slotType, stacksHandler) -> {
                IDynamicStackHandler dynStacks = stacksHandler.getStacks();
                for (int i = 0; i < dynStacks.getSlots(); i++) {
                    ItemStack stack = dynStacks.getStackInSlot(i);
                    if (!stack.isEmpty()) {
                        String serialized = VanillaSync.getNbtForStorage(stack);
                        flatMap.put(slotType + ":" + i, serialized);
                    }
                }
            });
        });

        String serializedData = flatMap.toString();

        // FIX: Use REPLACE INTO instead of separate INSERT/UPDATE to prevent silent
        // no-ops when the row doesn't exist yet (e.g. new player who died before first save)
        JDBCsetUp.executePreparedUpdate(
                "REPLACE INTO curios (uuid, curios_item) VALUES (?, ?)",
                player.getUUID().toString(), serializedData);
    }

    // ============================
    // Sophisticated Backpacks
    // ============================

    public static void storeSophisticatedBackpacks(Player player) {
        PlayerSync.LOGGER.info("Storing backpack data for player {}", player.getUUID());
        net.p3pp3rf1y.sophisticatedbackpacks.util.PlayerInventoryProvider.get().runOnBackpacks(player, (ItemStack backpackItem, String handler, String identifier, int slot) -> {
            net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.IBackpackWrapper backpackWrapper = net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper
                    .fromStack(backpackItem);

            Optional<UUID> uuidOpt = backpackWrapper.getContentsUuid();
            if (uuidOpt.isPresent()) {
                UUID contentsUuid = uuidOpt.get();

                // FIX: Read the full contents NBT from the wrapper's in-memory state,
                // not from BackpackStorage which may have stale data if the wrapper
                // hasn't flushed recent changes (e.g. upgrade modifications).
                // refreshInventoryForInputOutput triggers an internal save to BackpackStorage.
                try {
                    backpackWrapper.refreshInventoryForInputOutput();
                } catch (Exception ignored) {}

                CompoundTag backpackNbt = net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackStorage.get().getOrCreateBackpackContents(contentsUuid);
                saveStorageContents(contentsUuid, backpackNbt);
                PlayerSync.LOGGER.info("Saved backpack data for UUID {}", contentsUuid);
            } else {
                PlayerSync.LOGGER.warn("Backpack item in slot {} has no contentsUuid", slot);
            }
            return false;
        });
    }

    // ============================
    // Sophisticated Storage (barrels, shulkers, chests)
    // ============================

    /**
     * Scans the player's inventory for packed Sophisticated Storage items (barrels, shulkers, chests)
     * and saves their contents to the database.
     *
     * These items store their contents externally using a UUID reference, similar to backpacks.
     * The item's CustomData contains a "contentsUuid" field pointing to the storage data.
     */
    public static void storeSophisticatedStorageItems(Player player) {
        PlayerSync.LOGGER.info("Scanning inventory for Sophisticated Storage items for player {}", player.getUUID());
        scanAndStoreSophisticatedStorageInContainer(player.getInventory());
        // Also scan ender chest
        for (int i = 0; i < player.getEnderChestInventory().getContainerSize(); i++) {
            ItemStack stack = player.getEnderChestInventory().getItem(i);
            if (stack.isEmpty()) continue;
            storeSingleSophisticatedStorageItem(stack);
        }
    }

    private static void scanAndStoreSophisticatedStorageInContainer(Inventory inventory) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            storeSingleSophisticatedStorageItem(stack);
        }
    }

    private static void storeSingleSophisticatedStorageItem(ItemStack stack) {
        if (!isSophisticatedStorageItem(stack)) return;

        try {
            // FIX: Use the StackStorageWrapper API to get the UUID via DataComponent,
            // NOT CustomData extraction. In 1.21.1, the UUID is a proper DataComponent
            // managed by ModCoreDataComponents, not an NBT tag in CustomData.
            net.p3pp3rf1y.sophisticatedstorage.item.StackStorageWrapper wrapper =
                    net.p3pp3rf1y.sophisticatedstorage.item.StackStorageWrapper.fromStack(
                            net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer().registryAccess(), stack);
            Optional<UUID> uuidOpt = wrapper.getContentsUuid();
            if (uuidOpt.isEmpty()) return;

            UUID contentsUuid = uuidOpt.get();
            CompoundTag storageNbt = net.p3pp3rf1y.sophisticatedstorage.block.ItemContentsStorage.get()
                    .getOrCreateStorageContents(contentsUuid);
            if (storageNbt != null && !storageNbt.isEmpty()) {
                saveStorageContents(contentsUuid, storageNbt);
                PlayerSync.LOGGER.info("Saved Sophisticated Storage item data for UUID {}", contentsUuid);
            }
        } catch (Exception e) {
            PlayerSync.LOGGER.error("Error saving Sophisticated Storage data for item", e);
        }
    }

    /**
     * Restores packed Sophisticated Storage items' contents from the database.
     */
    public static void restoreSophisticatedStorageItems(Player player) {
        PlayerSync.LOGGER.info("Restoring Sophisticated Storage items for player {}", player.getUUID());
        restoreSophisticatedStorageInContainer(player.getInventory());
        // Also restore ender chest items
        for (int i = 0; i < player.getEnderChestInventory().getContainerSize(); i++) {
            ItemStack stack = player.getEnderChestInventory().getItem(i);
            if (stack.isEmpty()) continue;
            restoreSingleSophisticatedStorageItem(stack);
        }
    }

    private static void restoreSophisticatedStorageInContainer(Inventory inventory) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            restoreSingleSophisticatedStorageItem(stack);
        }
    }

    private static void restoreSingleSophisticatedStorageItem(ItemStack stack) {
        if (!isSophisticatedStorageItem(stack)) return;

        try {
            net.p3pp3rf1y.sophisticatedstorage.item.StackStorageWrapper wrapper =
                    net.p3pp3rf1y.sophisticatedstorage.item.StackStorageWrapper.fromStack(
                            net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer().registryAccess(), stack);
            Optional<UUID> uuidOpt = wrapper.getContentsUuid();
            if (uuidOpt.isEmpty()) return;

            UUID finalUuid = uuidOpt.get();
            restoreStorageContents(finalUuid, (nbt) -> {
                try {
                    net.p3pp3rf1y.sophisticatedstorage.block.ItemContentsStorage.get()
                            .setStorageContents(finalUuid, nbt);
                    PlayerSync.LOGGER.info("Restored Sophisticated Storage item data for UUID {}", finalUuid);
                } catch (Exception e) {
                    PlayerSync.LOGGER.error("Error restoring Sophisticated Storage data for UUID {}", finalUuid, e);
                }
            });
        } catch (Exception e) {
            PlayerSync.LOGGER.error("Error restoring Sophisticated Storage item", e);
        }
    }

    /**
     * Checks if an item is from the Sophisticated Storage mod by examining its registry name.
     */
    private static boolean isSophisticatedStorageItem(ItemStack stack) {
        try {
            net.minecraft.resources.ResourceLocation loc = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
            return loc != null && loc.getNamespace().equals("sophisticatedstorage");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Extracts the contents UUID from an item's custom data.
     * Used by Sophisticated Backpacks (key: "contentsUuid").
     */
    private static UUID extractContentsUuid(ItemStack stack) {
        return extractUuidFromCustomData(stack, "contentsUuid");
    }

    /**
     * Extracts the storage UUID from an item's custom data.
     * Used by Sophisticated Storage items - shulkers, barrels, chests (key: "storageUuid").
     */
    private static UUID extractStorageUuid(ItemStack stack) {
        return extractUuidFromCustomData(stack, "storageUuid");
    }

    /**
     * Generic UUID extraction from an item's CustomData by tag key name.
     * Handles both UUID compound format (most/leastSignificantBits) and string format.
     */
    private static UUID extractUuidFromCustomData(ItemStack stack, String tagKey) {
        try {
            if (!stack.has(DataComponents.CUSTOM_DATA)) return null;
            CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
            if (customData == null) return null;
            CompoundTag tag = customData.copyTag();
            if (tag.hasUUID(tagKey)) {
                return tag.getUUID(tagKey);
            }
            // Some versions use a string format
            if (tag.contains(tagKey)) {
                try {
                    return UUID.fromString(tag.getString(tagKey));
                } catch (IllegalArgumentException ignored) {}
            }
        } catch (Exception e) {
            PlayerSync.LOGGER.debug("Could not extract {} from item: {}", tagKey, e.getMessage());
        }
        return null;
    }

    // ============================
    // Refined Storage 2 Disks
    // ============================

    /**
     * Saves RS2 disk storage contents for all disks in the player's inventory.
     * RS2 disks reference their storage via a UUID DataComponent (storageReference).
     * The actual storage data lives in a world-level SavedData (StorageRepositoryImpl).
     * We extract individual entries from the saved data and store them in our DB.
     */
    /**
     * Saves RS2 disk storage by reading the SavedData .dat file directly from disk.
     * This avoids issues with the in-memory API format by reading the raw NBT that RS2 writes.
     * The SavedData file name is "refinedstorage_storages" and is stored in the overworld's data/ folder.
     */
    public static void storeRefinedStorageDisks(Player player) {
        if (!ModList.get().isLoaded("refinedstorage")) return;
        if (!(player instanceof net.minecraft.server.level.ServerPlayer sp)) return;

        List<UUID> diskUuids = collectRS2DiskUuids(player);
        if (diskUuids.isEmpty()) return;

        try {
            // Force RS2's SavedData to flush to disk before reading
            com.refinedmods.refinedstorage.common.api.storage.StorageRepository repo =
                    com.refinedmods.refinedstorage.common.api.RefinedStorageApi.INSTANCE.getStorageRepository(sp.serverLevel());
            if (repo instanceof net.minecraft.world.level.saveddata.SavedData sd) {
                sd.setDirty();
            }
            sp.getServer().overworld().getDataStorage().save();

            // Read the .dat file directly (getDataFile is private, use reflection)
            java.io.File datFile = getRS2DataFile(sp);
            if (datFile == null || !datFile.exists()) {
                PlayerSync.LOGGER.warn("RS2 storage data file not found: {}", datFile != null ? datFile.getAbsolutePath() : "<null>");
                return;
            }

            net.minecraft.nbt.CompoundTag fileNbt = net.minecraft.nbt.NbtIo.readCompressed(
                    datFile.toPath(), net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            // .dat file structure: { "data": { ...codec-encoded map... }, "DataVersion": int }
            net.minecraft.nbt.CompoundTag dataNbt = fileNbt.getCompound("data");

            for (UUID uuid : diskUuids) {
                String uuidStr = uuid.toString();
                // Search for the UUID key in the data (may be top-level or nested)
                net.minecraft.nbt.CompoundTag entryNbt = findRS2EntryInNbt(dataNbt, uuidStr);
                if (entryNbt != null && !entryNbt.isEmpty()) {
                    saveStorageContents(uuid, entryNbt);
                    PlayerSync.LOGGER.info("Saved RS2 disk data for UUID {} ({} tags)", uuid, entryNbt.getAllKeys().size());
                } else {
                    PlayerSync.LOGGER.warn("RS2 disk UUID {} not found in saved data. Keys: {}", uuid, dataNbt.getAllKeys());
                }
            }
        } catch (Exception e) {
            PlayerSync.LOGGER.error("Error saving RS2 disk data for player {}", player.getUUID(), e);
        }
    }

    /**
     * Restores RS2 disk storage by writing entries back into the SavedData .dat file
     * and reloading the repository. This ensures the data format matches exactly what RS2 expects.
     */
    public static void restoreRefinedStorageDisks(Player player) {
        if (!ModList.get().isLoaded("refinedstorage")) return;
        if (!(player instanceof net.minecraft.server.level.ServerPlayer sp)) return;

        List<UUID> diskUuids = collectRS2DiskUuids(player);
        if (diskUuids.isEmpty()) return;

        try {
            // Read the current .dat file
            var dataStorage = sp.getServer().overworld().getDataStorage();
            java.io.File datFile = getRS2DataFile(sp);

            net.minecraft.nbt.CompoundTag fileNbt;
            if (datFile.exists()) {
                fileNbt = net.minecraft.nbt.NbtIo.readCompressed(
                        datFile.toPath(), net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            } else {
                fileNbt = new net.minecraft.nbt.CompoundTag();
                fileNbt.put("data", new net.minecraft.nbt.CompoundTag());
            }
            net.minecraft.nbt.CompoundTag dataNbt = fileNbt.getCompound("data");

            boolean modified = false;
            for (UUID uuid : diskUuids) {
                final UUID fUuid = uuid;
                try (JDBCsetUp.QueryResult qr = JDBCsetUp.executePreparedQuery(
                        "SELECT backpack_nbt FROM backpack_data WHERE uuid=?", uuid.toString())) {
                    java.sql.ResultSet rs = qr.resultSet();
                    if (!rs.next()) continue;
                    String serialized = rs.getString("backpack_nbt");
                    if (serialized == null) continue;

                    CompoundTag entryNbt;
                    if (serialized.startsWith("BNBT:")) {
                        entryNbt = VanillaSync.deserializeBinaryBase64Tag(serialized);
                    } else {
                        String nbtStr = VanillaSync.deserializeString(serialized);
                        entryNbt = TagParser.parseTag(nbtStr);
                    }

                    // Inject into the data NBT at the right location
                    injectRS2EntryIntoNbt(dataNbt, uuid.toString(), entryNbt);
                    modified = true;
                    PlayerSync.LOGGER.info("Restored RS2 disk data for UUID {}", uuid);
                } catch (Exception e) {
                    PlayerSync.LOGGER.error("Error restoring RS2 disk data for UUID {}", fUuid, e);
                }
            }

            if (modified) {
                // Write the modified .dat file back and force RS2 to reload
                fileNbt.put("data", dataNbt);
                // FIX C-6: Atomic write - write to temp file then rename.
                // Direct write can corrupt the ENTIRE RS2 storage for the server on crash mid-write.
                java.nio.file.Path tmpPath = datFile.toPath().resolveSibling(datFile.getName() + ".tmp");
                net.minecraft.nbt.NbtIo.writeCompressed(fileNbt, tmpPath);
                java.nio.file.Files.move(tmpPath, datFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                PlayerSync.LOGGER.info("Wrote modified RS2 storage data file (atomic)");

                // Force the StorageRepository to reload from disk
                // The simplest way is via reflection on the data storage cache
                try {
                    // Remove the cached SavedData so RS2 reloads from file on next access
                    java.lang.reflect.Field cacheField = dataStorage.getClass().getDeclaredField("cache");
                    cacheField.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, ?> cache = (java.util.Map<String, ?>) cacheField.get(dataStorage);
                    cache.remove("refinedstorage_storages");
                    PlayerSync.LOGGER.info("Cleared RS2 storage cache to force reload");
                } catch (Exception e) {
                    PlayerSync.LOGGER.warn("Could not clear RS2 cache, data may need server restart to take effect", e);
                }
            }
        } catch (Exception e) {
            PlayerSync.LOGGER.error("Error restoring RS2 disk data for player {}", player.getUUID(), e);
        }
    }

    /**
     * Collects all RS2/ExtraDisks storage reference UUIDs from the player's inventory and ender chest.
     */
    private static List<UUID> collectRS2DiskUuids(Player player) {
        List<UUID> uuids = new ArrayList<>();
        // Check main inventory
        collectRS2DiskUuidsFromContainer(player.getInventory(), uuids);
        // Check ender chest
        for (int i = 0; i < player.getEnderChestInventory().getContainerSize(); i++) {
            ItemStack stack = player.getEnderChestInventory().getItem(i);
            if (stack.isEmpty()) continue;
            UUID ref = getRS2StorageReference(stack);
            if (ref != null) uuids.add(ref);
        }
        return uuids;
    }

    private static void collectRS2DiskUuidsFromContainer(Inventory inv, List<UUID> uuids) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            UUID ref = getRS2StorageReference(stack);
            if (ref != null) uuids.add(ref);
        }
    }

    /**
     * Extracts the storageReference UUID from an RS2 disk item using the RS2 DataComponent.
     * Returns null if the item is not an RS2 disk or doesn't have a storage reference.
     */
    private static UUID getRS2StorageReference(ItemStack stack) {
        try {
            net.minecraft.resources.ResourceLocation loc =
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (loc == null) return null; // FIX C-5: null check prevents NPE on unregistered items
            if (!loc.getNamespace().equals("refinedstorage") && !loc.getNamespace().equals("extradisks")) {
                return null;
            }
            net.minecraft.core.component.DataComponentType<UUID> storageRefType =
                    com.refinedmods.refinedstorage.common.content.DataComponents.INSTANCE.getStorageReference();
            return stack.get(storageRefType);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Gets the RS2 SavedData .dat file path using reflection on DimensionDataStorage.
     */
    private static java.io.File getRS2DataFile(net.minecraft.server.level.ServerPlayer sp) {
        try {
            var dataStorage = sp.getServer().overworld().getDataStorage();
            // DimensionDataStorage stores files in a "data" subfolder of the world directory
            // Use reflection to get the dataFolder field
            java.lang.reflect.Field dataFolderField = dataStorage.getClass().getDeclaredField("dataFolder");
            dataFolderField.setAccessible(true);
            java.io.File dataFolder = (java.io.File) dataFolderField.get(dataStorage);
            return new java.io.File(dataFolder, "refinedstorage_storages.dat");
        } catch (Exception e) {
            // Fallback: construct the path manually from the world directory
            try {
                java.nio.file.Path worldDir = sp.getServer().getServerDirectory();
                java.io.File levelName = worldDir.resolve(
                        sp.getServer().getWorldData().getLevelName()).toFile();
                return new java.io.File(new java.io.File(levelName, "data"), "refinedstorage_storages.dat");
            } catch (Exception e2) {
                PlayerSync.LOGGER.error("Failed to locate RS2 data file", e2);
                return null;
            }
        }
    }

    /**
     * Searches for a UUID entry in the RS2 saved data NBT.
     * Tries multiple levels of nesting since the codec format may vary.
     */
    private static net.minecraft.nbt.CompoundTag findRS2EntryInNbt(net.minecraft.nbt.CompoundTag dataNbt, String uuidStr) {
        // Direct key at top level
        if (dataNbt.contains(uuidStr, net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            return dataNbt.getCompound(uuidStr);
        }
        // Search one level deep in all compound sub-tags
        for (String key : dataNbt.getAllKeys()) {
            if (dataNbt.contains(key, net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                net.minecraft.nbt.CompoundTag sub = dataNbt.getCompound(key);
                if (sub.contains(uuidStr, net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                    return sub.getCompound(uuidStr);
                }
            }
            // Also check ListTag entries (some codecs encode maps as lists of pairs)
            if (dataNbt.contains(key, net.minecraft.nbt.Tag.TAG_LIST)) {
                net.minecraft.nbt.ListTag list = dataNbt.getList(key, net.minecraft.nbt.Tag.TAG_COMPOUND);
                for (int i = 0; i < list.size(); i++) {
                    net.minecraft.nbt.CompoundTag entry = list.getCompound(i);
                    // Check for {"uuid": "...", "data": {...}} pattern
                    if (entry.getString("uuid").equals(uuidStr) && entry.contains("data", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                        return entry.getCompound("data");
                    }
                    // Check for {"id": "...", ...} pattern
                    if (entry.getString("id").equals(uuidStr)) {
                        return entry;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Injects an RS2 storage entry back into the saved data NBT.
     * Mirrors the structure found during save.
     */
    private static void injectRS2EntryIntoNbt(net.minecraft.nbt.CompoundTag dataNbt, String uuidStr, net.minecraft.nbt.CompoundTag entryNbt) {
        // Put at top level (unboundedMap format)
        dataNbt.put(uuidStr, entryNbt);
    }
}
