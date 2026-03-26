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
     * Saves RS2 disk storage using SavedData.save() which serializes from MEMORY (not disk).
     * This avoids stale .dat file issues and doesn't call dataStorage.save() which crashes
     * with fastasyncworldsave.
     */
    @SuppressWarnings("unchecked")
    public static void storeRefinedStorageDisks(Player player) {
        if (!ModList.get().isLoaded("refinedstorage")) return;
        if (!(player instanceof net.minecraft.server.level.ServerPlayer sp)) return;

        List<UUID> diskUuids = collectRS2DiskUuids(player);
        if (diskUuids.isEmpty()) return;

        try {
            com.refinedmods.refinedstorage.common.api.storage.StorageRepository repo =
                    com.refinedmods.refinedstorage.common.api.RefinedStorageApi.INSTANCE.getStorageRepository(sp.serverLevel());
            if (!(repo instanceof net.minecraft.world.level.saveddata.SavedData sd)) return;

            // STRATEGY: Use save() to get the full serialized NBT, search for UUID entries.
            // If save() format doesn't match our parsing, fall back to reflection on the
            // internal entries map + codec to serialize individual entries.
            net.minecraft.nbt.CompoundTag fullNbt = sd.save(new net.minecraft.nbt.CompoundTag(), sp.getServer().registryAccess());

            // Log structure for debugging
            PlayerSync.LOGGER.info("RS2 save() NBT: {} keys, types: {}", fullNbt.getAllKeys().size(), describeNbtStructure(fullNbt));

            for (UUID uuid : diskUuids) {
                String uuidStr = uuid.toString();
                net.minecraft.nbt.CompoundTag entryNbt = findRS2EntryInNbt(fullNbt, uuidStr);
                if (entryNbt != null && !entryNbt.isEmpty()) {
                    saveStorageContents(uuid, entryNbt);
                    PlayerSync.LOGGER.info("Saved RS2 disk data for UUID {} via save() NBT", uuid);
                    continue;
                }

                // Fallback: use reflection to get the codec and serialize the single entry
                if (!repo.get(uuid).isPresent()) {
                    PlayerSync.LOGGER.debug("RS2 disk UUID {} has no storage data (empty disk)", uuid);
                    continue;
                }

                PlayerSync.LOGGER.info("RS2 UUID {} not in save() NBT, using codec fallback", uuid);
                try {
                    // Get the map codec from StorageRepositoryImpl
                    java.lang.reflect.Method getMapCodecMethod =
                            repo.getClass().getDeclaredMethod("createCodec", Runnable.class);
                    getMapCodecMethod.setAccessible(true);
                    @SuppressWarnings("rawtypes")
                    com.mojang.serialization.Codec codec = (com.mojang.serialization.Codec)
                            getMapCodecMethod.invoke(null, (Runnable) () -> {});

                    // Get the entries map via reflection
                    java.lang.reflect.Field entriesField = repo.getClass().getDeclaredField("entries");
                    entriesField.setAccessible(true);
                    java.util.Map<UUID, ?> entries = (java.util.Map<UUID, ?>) entriesField.get(repo);

                    Object storageEntry = entries.get(uuid);
                    if (storageEntry == null) continue;

                    // Encode a single-entry map to NBT using the codec
                    java.util.Map<UUID, Object> singleEntry = java.util.Map.of(uuid, storageEntry);
                    var ops = sp.getServer().registryAccess().createSerializationContext(
                            net.minecraft.nbt.NbtOps.INSTANCE);
                    var encodeResult = codec.encodeStart(ops, singleEntry);
                    if (encodeResult.result().isPresent()) {
                        net.minecraft.nbt.Tag encodedTag = (net.minecraft.nbt.Tag) encodeResult.result().get();
                        if (encodedTag instanceof net.minecraft.nbt.CompoundTag encodedCompound) {
                            saveStorageContents(uuid, encodedCompound);
                            PlayerSync.LOGGER.info("Saved RS2 disk data for UUID {} via codec reflection", uuid);
                        }
                    } else {
                        PlayerSync.LOGGER.error("RS2 codec encode failed for UUID {}: {}", uuid, encodeResult.error());
                    }
                } catch (Exception reflectEx) {
                    PlayerSync.LOGGER.error("RS2 reflection fallback failed for UUID {}", uuid, reflectEx);
                }
            }
        } catch (Exception e) {
            PlayerSync.LOGGER.error("Error saving RS2 disk data for player {}", player.getUUID(), e);
        }
    }

    /** Describes the top-level NBT structure for debugging */
    private static String describeNbtStructure(net.minecraft.nbt.CompoundTag tag) {
        StringBuilder sb = new StringBuilder("{");
        for (String key : tag.getAllKeys()) {
            net.minecraft.nbt.Tag val = tag.get(key);
            sb.append(key).append("=").append(val != null ? val.getType().getName() : "null");
            if (val instanceof net.minecraft.nbt.CompoundTag ct) {
                sb.append("(").append(ct.getAllKeys().size()).append(" keys)");
            } else if (val instanceof net.minecraft.nbt.ListTag lt) {
                sb.append("[").append(lt.size()).append(" entries]");
            }
            sb.append(", ");
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * Restores RS2 disk storage using the codec to decode entries and repo.set() to inject.
     * The saved data was encoded via the map codec during save, so we decode with the same codec.
     */
    @SuppressWarnings("unchecked")
    public static void restoreRefinedStorageDisks(Player player) {
        if (!ModList.get().isLoaded("refinedstorage")) return;
        if (!(player instanceof net.minecraft.server.level.ServerPlayer sp)) return;

        List<UUID> diskUuids = collectRS2DiskUuids(player);
        if (diskUuids.isEmpty()) return;

        try {
            com.refinedmods.refinedstorage.common.api.storage.StorageRepository repo =
                    com.refinedmods.refinedstorage.common.api.RefinedStorageApi.INSTANCE.getStorageRepository(sp.serverLevel());

            // Get the map codec via reflection (same codec used for save)
            @SuppressWarnings("rawtypes")
            com.mojang.serialization.Codec mapCodec;
            try {
                java.lang.reflect.Method getMapCodecMethod =
                        repo.getClass().getDeclaredMethod("createCodec", Runnable.class);
                getMapCodecMethod.setAccessible(true);
                mapCodec = (com.mojang.serialization.Codec) getMapCodecMethod.invoke(null, (Runnable) () -> {});
            } catch (Exception e) {
                PlayerSync.LOGGER.error("Cannot get RS2 map codec, disk restore will fail", e);
                return;
            }

            var ops = sp.getServer().registryAccess().createSerializationContext(
                    net.minecraft.nbt.NbtOps.INSTANCE);
            @SuppressWarnings("rawtypes")
            final com.mojang.serialization.Codec fCodec = mapCodec;

            for (UUID uuid : diskUuids) {
                restoreStorageContents(uuid, (storedNbt) -> {
                    try {
                        @SuppressWarnings("unchecked")
                        com.mojang.serialization.DataResult<?> dataResult = fCodec.decode(ops, storedNbt);
                        Optional<?> opt = dataResult.result();
                        if (opt.isPresent()) {
                            com.mojang.datafixers.util.Pair<?, ?> pair = (com.mojang.datafixers.util.Pair<?, ?>) opt.get();
                            @SuppressWarnings("unchecked")
                            java.util.Map<UUID, ?> decoded = (java.util.Map<UUID, ?>) pair.getFirst();
                            for (java.util.Map.Entry<UUID, ?> entry : decoded.entrySet()) {
                                repo.set(entry.getKey(),
                                        (com.refinedmods.refinedstorage.common.api.storage.SerializableStorage) entry.getValue());
                                PlayerSync.LOGGER.info("Restored RS2 disk data for UUID {}", entry.getKey());
                            }
                        } else {
                            PlayerSync.LOGGER.error("RS2 codec decode failed for UUID {}", uuid);
                        }
                    } catch (Exception e) {
                        PlayerSync.LOGGER.error("Error restoring RS2 disk data for UUID {}", uuid, e);
                    }
                });
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

}
