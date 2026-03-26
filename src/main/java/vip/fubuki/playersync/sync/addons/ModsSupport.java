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
                    "UPDATE curios SET curios_item=? WHERE uuid=?",
                    cached.serializedData, playerUuid.toString());
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

        // Use prepared statements to prevent SQL injection / data corruption
        if (init) {
            JDBCsetUp.executePreparedUpdate(
                    "INSERT INTO curios (uuid, curios_item) VALUES (?, ?)",
                    player.getUUID().toString(), serializedData);
        } else {
            JDBCsetUp.executePreparedUpdate(
                    "UPDATE curios SET curios_item=? WHERE uuid=?",
                    serializedData, player.getUUID().toString());
        }
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
        Inventory inventory = player.getInventory();

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;

            // Check if this item is from the sophisticatedstorage namespace
            String itemId = stack.getItem().toString();
            if (!isSophisticatedStorageItem(stack)) continue;

            // Try to extract contentsUuid from the item's custom data
            UUID contentsUuid = extractContentsUuid(stack);
            if (contentsUuid == null) continue;

            try {
                // Read the storage contents from the world save data via BackpackStorage
                // Sophisticated Storage uses the same BackpackStorage mechanism from sophisticatedcore
                CompoundTag storageNbt = net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackStorage.get().getOrCreateBackpackContents(contentsUuid);
                if (storageNbt != null && !storageNbt.isEmpty()) {
                    saveStorageContents(contentsUuid, storageNbt);
                    PlayerSync.LOGGER.info("Saved Sophisticated Storage item data for UUID {}", contentsUuid);
                }
            } catch (Exception e) {
                PlayerSync.LOGGER.error("Error saving Sophisticated Storage data for UUID {}", contentsUuid, e);
            }
        }
    }

    /**
     * Restores packed Sophisticated Storage items' contents from the database.
     */
    public static void restoreSophisticatedStorageItems(Player player) {
        PlayerSync.LOGGER.info("Restoring Sophisticated Storage items for player {}", player.getUUID());
        Inventory inventory = player.getInventory();

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;

            if (!isSophisticatedStorageItem(stack)) continue;

            UUID contentsUuid = extractContentsUuid(stack);
            if (contentsUuid == null) continue;

            restoreStorageContents(contentsUuid, (nbt) -> {
                try {
                    net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackStorage.get().setBackpackContents(contentsUuid, nbt);
                    PlayerSync.LOGGER.info("Restored Sophisticated Storage item data for UUID {}", contentsUuid);
                } catch (Exception e) {
                    PlayerSync.LOGGER.error("Error restoring Sophisticated Storage data for UUID {}", contentsUuid, e);
                }
            });
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
     * Extracts the contents UUID from an item's custom data (used by Sophisticated Core).
     * Both Sophisticated Backpacks and Sophisticated Storage store a "contentsUuid" in the item's NBT.
     */
    private static UUID extractContentsUuid(ItemStack stack) {
        try {
            if (!stack.has(DataComponents.CUSTOM_DATA)) return null;
            CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
            if (customData == null) return null;
            CompoundTag tag = customData.copyTag();
            if (tag.hasUUID("contentsUuid")) {
                return tag.getUUID("contentsUuid");
            }
            // Some versions use a string format
            if (tag.contains("contentsUuid")) {
                try {
                    return UUID.fromString(tag.getString("contentsUuid"));
                } catch (IllegalArgumentException ignored) {}
            }
        } catch (Exception e) {
            PlayerSync.LOGGER.debug("Could not extract contentsUuid from item: {}", e.getMessage());
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
    public static void storeRefinedStorageDisks(Player player) {
        if (!ModList.get().isLoaded("refinedstorage")) return;
        if (!(player instanceof net.minecraft.server.level.ServerPlayer sp)) return;

        List<UUID> diskUuids = collectRS2DiskUuids(player);
        if (diskUuids.isEmpty()) return;

        try {
            com.refinedmods.refinedstorage.common.api.storage.StorageRepository repo =
                    com.refinedmods.refinedstorage.common.api.RefinedStorageApi.INSTANCE.getStorageRepository(sp.serverLevel());

            // Serialize the full repository to NBT via SavedData.save()
            if (repo instanceof net.minecraft.world.level.saveddata.SavedData savedData) {
                net.minecraft.nbt.CompoundTag fullNbt = new net.minecraft.nbt.CompoundTag();
                savedData.save(fullNbt, sp.getServer().registryAccess());

                for (UUID uuid : diskUuids) {
                    net.minecraft.nbt.CompoundTag entryNbt = extractRS2Entry(fullNbt, uuid);
                    if (entryNbt != null && !entryNbt.isEmpty()) {
                        // Store the entry NBT along with a wrapper that includes the UUID key
                        // so we can reconstruct the map format on restore
                        net.minecraft.nbt.CompoundTag wrapper = new net.minecraft.nbt.CompoundTag();
                        wrapper.put(uuid.toString(), entryNbt);
                        saveStorageContents(uuid, wrapper);
                        PlayerSync.LOGGER.info("Saved RS2 disk data for UUID {}", uuid);
                    }
                }
            }
        } catch (Exception e) {
            PlayerSync.LOGGER.error("Error saving RS2 disk data for player {}", player.getUUID(), e);
        }
    }

    /**
     * Restores RS2 disk storage contents from the database.
     * Uses reflection to access the StorageRepositoryImpl's codec for proper deserialization,
     * then calls the public set() method to inject entries into the live repository.
     */
    public static void restoreRefinedStorageDisks(Player player) {
        if (!ModList.get().isLoaded("refinedstorage")) return;
        if (!(player instanceof net.minecraft.server.level.ServerPlayer sp)) return;

        List<UUID> diskUuids = collectRS2DiskUuids(player);
        if (diskUuids.isEmpty()) return;

        try {
            com.refinedmods.refinedstorage.common.api.storage.StorageRepository repo =
                    com.refinedmods.refinedstorage.common.api.RefinedStorageApi.INSTANCE.getStorageRepository(sp.serverLevel());

            for (UUID uuid : diskUuids) {
                // Check if storage already exists on this server (don't overwrite)
                if (repo.get(uuid).isPresent()) {
                    PlayerSync.LOGGER.debug("RS2 storage {} already exists on this server, skipping restore", uuid);
                    continue;
                }

                restoreStorageContents(uuid, (nbt) -> {
                    try {
                        injectRS2StorageEntry(repo, nbt, sp);
                    } catch (Exception e) {
                        PlayerSync.LOGGER.error("Error injecting RS2 storage for UUID {}", uuid, e);
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
     * Extracts an individual storage entry from the full StorageRepository NBT by UUID.
     * The save() format uses UUID strings as CompoundTag keys (unboundedMap codec).
     */
    private static net.minecraft.nbt.CompoundTag extractRS2Entry(net.minecraft.nbt.CompoundTag fullNbt, UUID uuid) {
        String uuidStr = uuid.toString();
        // Direct key lookup (standard unboundedMap format)
        if (fullNbt.contains(uuidStr, net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            return fullNbt.getCompound(uuidStr);
        }
        // Some SavedData implementations wrap data under a "data" key
        for (String key : fullNbt.getAllKeys()) {
            if (fullNbt.contains(key, net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                net.minecraft.nbt.CompoundTag sub = fullNbt.getCompound(key);
                if (sub.contains(uuidStr, net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                    return sub.getCompound(uuidStr);
                }
            }
        }
        return null;
    }

    /**
     * Injects a storage entry back into the RS2 StorageRepository.
     * Uses the repository's codec (via reflection) to properly deserialize the entry,
     * then calls set() to inject it into the live repository.
     */
    @SuppressWarnings("unchecked")
    private static void injectRS2StorageEntry(
            com.refinedmods.refinedstorage.common.api.storage.StorageRepository repo,
            net.minecraft.nbt.CompoundTag wrapperNbt,
            net.minecraft.server.level.ServerPlayer sp) throws Exception {

        // The wrapper contains { "uuid-string": { ...entry data... } }
        // We need to decode this using the same codec that StorageRepositoryImpl uses

        // Get the map codec via reflection from StorageRepositoryImpl
        java.lang.reflect.Method getMapCodecMethod =
                repo.getClass().getDeclaredMethod("getMapCodec", Runnable.class);
        getMapCodecMethod.setAccessible(true);

        @SuppressWarnings("rawtypes")
        com.mojang.serialization.Codec codec = (com.mojang.serialization.Codec)
                getMapCodecMethod.invoke(null, (Runnable) () -> {});

        // Decode the single-entry wrapper using the codec
        var ops = sp.getServer().registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);
        com.mojang.serialization.DataResult<?> dataResult = codec.decode(ops, wrapperNbt);

        Optional<?> resultOpt = dataResult.result();
        if (resultOpt.isPresent()) {
            // DataResult contains Pair<Map<UUID, SerializableStorage>, Tag>
            com.mojang.datafixers.util.Pair<?, ?> pair = (com.mojang.datafixers.util.Pair<?, ?>) resultOpt.get();
            @SuppressWarnings("unchecked")
            Map<UUID, ?> decoded = (Map<UUID, ?>) pair.getFirst();
            for (Map.Entry<UUID, ?> entry : decoded.entrySet()) {
                repo.set(entry.getKey(),
                        (com.refinedmods.refinedstorage.common.api.storage.SerializableStorage) entry.getValue());
                PlayerSync.LOGGER.info("Restored RS2 disk storage for UUID {}", entry.getKey());
            }
        } else {
            PlayerSync.LOGGER.warn("Failed to decode RS2 storage data from wrapper NBT: {}", wrapperNbt);
        }
    }
}
