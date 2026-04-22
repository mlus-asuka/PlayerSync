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
import vip.fubuki.playersync.util.Tables;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;


public class ModsSupport {
    public void doBackPackRestore(Player player) {
        if (ModList.get().isLoaded("sophisticatedbackpacks")) {
            PlayerSync.LOGGER.info("Restoring backpack data for player {}", player.getUUID());
            // Restore backpacks from main inventory
            net.p3pp3rf1y.sophisticatedbackpacks.util.PlayerInventoryProvider.get().runOnBackpacks(player, (ItemStack backpackItem, String handler, String identifier, int slot) -> {
                restoreSingleBackpack(backpackItem);
                return false;
            });
            // FIX: Also restore backpacks from ender chest (save side scans ender chest too)
            for (int i = 0; i < player.getEnderChestInventory().getContainerSize(); i++) {
                ItemStack stack = player.getEnderChestInventory().getItem(i);
                if (!stack.isEmpty()) {
                    restoreSingleBackpack(stack);
                }
            }
        }
    }

    private void restoreSingleBackpack(ItemStack stack) {
        try {
            net.minecraft.resources.ResourceLocation loc = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (loc == null || !loc.getNamespace().equals("sophisticatedbackpacks")) return;

            net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.IBackpackWrapper backpackWrapper =
                    net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper.fromStack(stack);
            Optional<UUID> uuidOpt = backpackWrapper.getContentsUuid();
            if (uuidOpt.isPresent()) {
                UUID contentsUuid = uuidOpt.get();
                restoreStorageContents(contentsUuid, (nbt) -> {
                    // ROOT CAUSE FIX — BackpackStorage.setBackpackContents() upstream is a
                    // shallow MERGE, not a replace, when the UUID already exists. On any
                    // server that previously loaded this backpack (re-join, multi-world,
                    // .dat persisted), old sub-tags survive the "restore" → duplication.
                    // Removing first guarantees a clean replace.
                    net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackStorage store =
                            net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackStorage.get();
                    // FIX P0-1: two-step clear to guarantee no stale data merges through.
                    //   1) public removeBackpackContents (preferred API, since 3.x)
                    //   2) reflection fallback: clear the internal map entry directly
                    // Any remaining sub-tag after step 1 could leak stale items — step 2 is
                    // our belt-and-suspenders against upstream regressions.
                    boolean cleared = false;
                    try {
                        store.removeBackpackContents(contentsUuid);
                        cleared = true;
                    } catch (Throwable t) {
                        PlayerSync.LOGGER.warn("Backpack removeBackpackContents failed for UUID {} ({}): falling back to reflection clear",
                                contentsUuid, t.getClass().getSimpleName());
                    }
                    if (!cleared) clearBackpackStorageReflective(store, contentsUuid);
                    // Defensive copy: never hand upstream a tag that might be mutated elsewhere.
                    CompoundTag fresh = nbt.copy();
                    store.setBackpackContents(contentsUuid, fresh);
                    PlayerSync.LOGGER.info("[restore-backpack] uuid={} nbt_keys={} cleared_via={}",
                            contentsUuid, fresh.getAllKeys().size(), cleared ? "api" : "reflection");
                });
            }
        } catch (Exception e) {
            PlayerSync.LOGGER.error("[restore-backpack] unexpected error restoring backpack {}", stack, e);
        }
    }

    /**
     * Reflection fallback that zeroes out the {@code BackpackStorage} entry for the
     * given UUID. Only used if the public {@code removeBackpackContents} call fails.
     */
    private static void clearBackpackStorageReflective(
            net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackStorage store, UUID uuid) {
        try {
            // Common SavedData field names: "backpackContents" or inherited "data"
            for (java.lang.reflect.Field f : store.getClass().getDeclaredFields()) {
                if (java.util.Map.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Object map = f.get(store);
                    if (map instanceof java.util.Map<?, ?> m) {
                        ((java.util.Map<Object, Object>) m).remove(uuid);
                        ((java.util.Map<Object, Object>) m).remove(uuid.toString());
                    }
                }
            }
            store.setDirty();
        } catch (Throwable t) {
            PlayerSync.LOGGER.error("[restore-backpack] reflection clear failed for {}: {}", uuid, t.getMessage());
        }
    }

    /**
     * Generic method to restore storage contents from DB for a given UUID.
     * Used for both Sophisticated Backpacks and Sophisticated Storage items.
     */
    private static void restoreStorageContents(UUID contentsUuid, StorageRestoreCallback callback) {
        try (JDBCsetUp.QueryResult qr = JDBCsetUp.executePreparedQuery(
                "SELECT backpack_nbt FROM " + Tables.backpackData() + " WHERE uuid=?", contentsUuid.toString())) {
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
    /**
     * Saves storage contents to DB, but ONLY if the NBT contains real data.
     * If the NBT is empty/default (wrapper didn't flush to SavedData yet),
     * we skip the save to avoid overwriting real data in the DB with empty content.
     * This prevents data loss when the in-memory SavedData doesn't have the latest
     * wrapper state (common with Sophisticated Backpacks/Storage).
     */
    private static void saveStorageContents(UUID contentsUuid, CompoundTag nbt) {
        // Only skip truly empty CompoundTag (no keys at all) — this happens when
        // getOrCreateStorageContents() creates a blank entry because the wrapper
        // hasn't flushed to SavedData yet. A backpack/shulker that the player
        // legitimately emptied still has structural keys (e.g. empty "items" list),
        // so nbt.isEmpty() is false and the save proceeds correctly.
        // Previous guard used nbt.size() <= 1 which also blocked legitimately emptied
        // containers, causing item duplication on the next login.
        if (nbt == null || nbt.isEmpty()) {
            try (JDBCsetUp.QueryResult qr = JDBCsetUp.executePreparedQuery(
                    "SELECT LENGTH(backpack_nbt) AS len FROM " + Tables.backpackData() + " WHERE uuid=?", contentsUuid.toString())) {
                java.sql.ResultSet rs = qr.resultSet();
                if (rs.next() && rs.getInt("len") > 50) {
                    PlayerSync.LOGGER.debug("Skipping save of empty NBT for UUID {} - DB has {} bytes of real data",
                            contentsUuid, rs.getInt("len"));
                    return;
                }
            } catch (Exception ignored) {}
        }

        String serialized = VanillaSync.serializeTagToBinaryBase64(nbt);
        try {
            // FIX INTEGRITY (E): REPLACE INTO silently overwrote backpack rows even when
            // another server had already claimed the owning player. We cannot easily
            // add a last_server guard to backpack_data directly (it is keyed by
            // storage UUID, not player UUID — no link to player_data). So we keep the
            // REPLACE here but expect upper layers (`saveBackpackSnapshots`) to be called
            // only after the player_data transaction commit has run under the last_server
            // guard, which is the case in writeSnapshotToDB's caller chain.
            JDBCsetUp.executePreparedUpdate(
                    "REPLACE INTO " + Tables.backpackData() + " (uuid, backpack_nbt) VALUES (?, ?)",
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
                "SELECT curios_item FROM " + Tables.curios() + " WHERE uuid=?", player.getUUID().toString())) {
            ResultSet rs = qr.resultSet();
            if (!rs.next()) {
                // No stored data; perform an initial save.
                StoreCurios(player, true);
                return;
            }
            curiosData = rs.getString("curios_item");
        }

        ICuriosItemHandler handler = handlerOpt.get();

        // FIX A2/A3: clear BOTH functional and cosmetic slots first to wipe stale .dat
        // data, then restore from DB if valid.
        handler.getCurios().forEach((slotType, stacksHandler) -> {
            IDynamicStackHandler dynStacks = stacksHandler.getStacks();
            for (int i = 0; i < dynStacks.getSlots(); i++) {
                dynStacks.setStackInSlot(i, ItemStack.EMPTY);
            }
            IDynamicStackHandler cos = stacksHandler.getCosmeticStacks();
            for (int i = 0; i < cos.getSlots(); i++) {
                cos.setStackInSlot(i, ItemStack.EMPTY);
            }
        });

        if (curiosData == null || curiosData.length() <= 2) {
            PlayerSync.LOGGER.debug("Empty curios data for player {}, slots cleared", player.getUUID());
            return;
        }

        Map<String, String> storedMap = LocalJsonUtil.StringToMap(curiosData);
        if (storedMap.isEmpty()) {
            PlayerSync.LOGGER.debug("No curios entries for player {}, slots cleared", player.getUUID());
            return;
        }

        // Restore each saved item. Support both new "cos:slotType:index" cosmetic keys
        // and legacy "slotType:index" functional-only keys.
        for (Map.Entry<String, String> entry : storedMap.entrySet()) {
            String compositeKey = entry.getKey();
            boolean cosmetic = compositeKey.startsWith("cos:");
            String remaining = cosmetic ? compositeKey.substring(4) : compositeKey;
            int lastColon = remaining.lastIndexOf(':');
            if (lastColon < 0) continue;

            String slotType = remaining.substring(0, lastColon);
            int slotIndex;
            try {
                slotIndex = Integer.parseInt(remaining.substring(lastColon + 1));
            } catch (NumberFormatException ex) {
                continue;
            }

            String serialized = entry.getValue();
            try {
                ItemStack stack = VanillaSync.deserializeAndCreatePlaceholderIfNeeded(serialized);
                if (handler.getCurios().containsKey(slotType)) {
                    ICurioStacksHandler stacksHandler = handler.getCurios().get(slotType);
                    IDynamicStackHandler dynStacks = cosmetic
                            ? stacksHandler.getCosmeticStacks()
                            : stacksHandler.getStacks();
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
                    "REPLACE INTO " + Tables.curios() + " (uuid, curios_item) VALUES (?, ?)",
                    playerUuid.toString(), cached.serializedData);
            CuriosCache.curiosCache.remove(playerUuid);
        } else {
            // Fallback: try to read from API (may be empty for dead players)
            StoreCurios(player, false);
        }
    }

    /**
     * Snapshots Curios data into a serialized string on the main thread (no DB write).
     * Returns the serialized data string, or null if no curios data.
     */
    public static String snapshotCuriosData(Player player) {
        if (!ModList.get().isLoaded("curios")) return null;
        Optional<ICuriosItemHandler> handlerOpt = CuriosApi.getCuriosInventory(player);
        // FIX ANTI-LOSS (A2): if the handler could not be resolved (capability not yet
        // attached, or Curios mod issue), return null so writeSnapshotToDB SKIPS the write
        // and preserves whatever data is already in DB. Returning "{}" here would overwrite
        // a legitimate curios record with an empty one and destroy the player's items.
        if (handlerOpt.isEmpty()) {
            PlayerSync.LOGGER.warn("Curios handler unavailable while snapshotting {} — skipping curios write", player.getUUID());
            return null;
        }
        Map<String, String> flatMap = new HashMap<>();
        ICuriosItemHandler handler = handlerOpt.get();
        // FIX DATA-LOSS (A2): sync BOTH functional stacks and cosmetic stacks. The prior
        // implementation only captured getStacks() → every cosmetic item equipped in a
        // Curios cosmetic slot was silently wiped across server transfers. Cosmetic slots
        // are identified by the "cos:" prefix in the composite key so apply/clear can
        // distinguish them without a schema change.
        handler.getCurios().forEach((slotType, stacksHandler) -> {
            IDynamicStackHandler dynStacks = stacksHandler.getStacks();
            for (int i = 0; i < dynStacks.getSlots(); i++) {
                ItemStack stack = dynStacks.getStackInSlot(i);
                if (!stack.isEmpty()) {
                    flatMap.put(slotType + ":" + i, VanillaSync.getNbtForStorage(stack));
                }
            }
            IDynamicStackHandler cosStacks = stacksHandler.getCosmeticStacks();
            for (int i = 0; i < cosStacks.getSlots(); i++) {
                ItemStack stack = cosStacks.getStackInSlot(i);
                if (!stack.isEmpty()) {
                    flatMap.put("cos:" + slotType + ":" + i, VanillaSync.getNbtForStorage(stack));
                }
            }
        });
        return flatMap.toString();
    }

    /**
     * Applies pre-read curios data to the player entity (NO DB access).
     * Used by doPlayerJoin to avoid DB reads on the main thread.
     */
    public static void applyCuriosFromData(Player player, String curiosData) {
        if (!ModList.get().isLoaded("curios")) return;

        Optional<ICuriosItemHandler> handlerOpt = CuriosApi.getCuriosInventory(player);
        if (handlerOpt.isEmpty()) {
            PlayerSync.LOGGER.warn("Could not get Curios handler for player {} during apply", player.getUUID());
            return;
        }

        ICuriosItemHandler handler = handlerOpt.get();

        // FIX ANTI-DUPLICATION (A2+A3): clear BOTH functional and cosmetic stacks first,
        // even when DB data is empty. Without this, stale curios loaded from the .dat
        // persist when the DB has no entry → dup across servers. Cosmetic stacks also
        // needed clearing or cosmetic-dup persisted asymmetrically.
        for (Map.Entry<String, ICurioStacksHandler> entry : handler.getCurios().entrySet()) {
            IDynamicStackHandler stacks = entry.getValue().getStacks();
            for (int i = 0; i < stacks.getSlots(); i++) {
                stacks.setStackInSlot(i, ItemStack.EMPTY);
            }
            IDynamicStackHandler cos = entry.getValue().getCosmeticStacks();
            for (int i = 0; i < cos.getSlots(); i++) {
                cos.setStackInSlot(i, ItemStack.EMPTY);
            }
        }

        // If no data to restore, we're done (slots already cleared above)
        if (curiosData == null || curiosData.length() <= 2) return;

        Map<String, String> storedMap = LocalJsonUtil.StringToMap(curiosData);
        if (storedMap.isEmpty()) return;

        // Restore items from pre-read data. Cosmetic slots use the "cos:slotType:index"
        // composite key; functional slots use "slotType:index".
        for (Map.Entry<String, String> entry : storedMap.entrySet()) {
            String compositeKey = entry.getKey();
            boolean cosmetic = compositeKey.startsWith("cos:");
            String remaining = cosmetic ? compositeKey.substring(4) : compositeKey;
            int lastColon = remaining.lastIndexOf(':');
            if (lastColon < 0) continue;
            String slotType = remaining.substring(0, lastColon);
            int slotIndex;
            try { slotIndex = Integer.parseInt(remaining.substring(lastColon + 1)); }
            catch (NumberFormatException e) { continue; }

            try {
                ItemStack stack = VanillaSync.deserializeAndCreatePlaceholderIfNeeded(entry.getValue());
                ICurioStacksHandler stacksHandler = handler.getCurios().get(slotType);
                if (stacksHandler != null) {
                    IDynamicStackHandler stacks = cosmetic
                            ? stacksHandler.getCosmeticStacks()
                            : stacksHandler.getStacks();
                    if (slotIndex < stacks.getSlots()) {
                        stacks.setStackInSlot(slotIndex, stack);
                    }
                }
            } catch (Exception e) {
                PlayerSync.LOGGER.error("Error applying curios slot {} ({}:{})", compositeKey, slotType, slotIndex, e);
            }
        }
        PlayerSync.LOGGER.info("Applied curios data for player {} from pre-read data", player.getUUID());
    }

    public void StoreCurios(Player player, boolean init) throws SQLException {
        if (!ModList.get().isLoaded("curios")) return;

        Optional<ICuriosItemHandler> handlerOpt = CuriosApi.getCuriosInventory(player);
        // FIX P1-1: if the Curios handler is unavailable (dead player, Curios mod
        // init race, capability detached), do NOT write an empty flatMap to DB —
        // that wipes the player's real curios. Log and skip instead.
        if (handlerOpt.isEmpty()) {
            PlayerSync.LOGGER.warn("[store-curios] handler unavailable for {} — skipping write to avoid wiping DB data",
                    player.getUUID());
            return;
        }
        Map<String, String> flatMap = new HashMap<>();

        handlerOpt.ifPresent(handler -> {
            handler.getCurios().forEach((slotType, stacksHandler) -> {
                IDynamicStackHandler dynStacks = stacksHandler.getStacks();
                for (int i = 0; i < dynStacks.getSlots(); i++) {
                    ItemStack stack = dynStacks.getStackInSlot(i);
                    if (!stack.isEmpty()) {
                        flatMap.put(slotType + ":" + i, VanillaSync.getNbtForStorage(stack));
                    }
                }
                // FIX A2: cosmetic stacks must be captured symmetrically with snapshotCuriosData.
                IDynamicStackHandler cosStacks = stacksHandler.getCosmeticStacks();
                for (int i = 0; i < cosStacks.getSlots(); i++) {
                    ItemStack stack = cosStacks.getStackInSlot(i);
                    if (!stack.isEmpty()) {
                        flatMap.put("cos:" + slotType + ":" + i, VanillaSync.getNbtForStorage(stack));
                    }
                }
            });
        });

        String serializedData = flatMap.toString();

        // FIX: Use REPLACE INTO instead of separate INSERT/UPDATE to prevent silent
        // no-ops when the row doesn't exist yet (e.g. new player who died before first save)
        JDBCsetUp.executePreparedUpdate(
                "REPLACE INTO " + Tables.curios() + " (uuid, curios_item) VALUES (?, ?)",
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

                // FIX: Read the full contents NBT from the wrapper's in-memory state.
                // NOTE: despite earlier comments, refreshInventoryForInputOutput() does
                // NOT actively flush to BackpackStorage — it resets the IO handler cache
                // and runs the change callbacks. The live CompoundTag in BackpackStorage
                // is already kept up to date by handler writes, so reading it next is safe.
                try {
                    backpackWrapper.refreshInventoryForInputOutput();
                } catch (Exception e) {
                    PlayerSync.LOGGER.warn("refreshInventoryForInputOutput failed for backpack {} of player {} — saved NBT may be slightly stale",
                            contentsUuid, player.getUUID(), e);
                }

                CompoundTag backpackNbt = net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackStorage.get().getOrCreateBackpackContents(contentsUuid);
                saveStorageContents(contentsUuid, backpackNbt);
                PlayerSync.LOGGER.info("Saved backpack data for UUID {}", contentsUuid);
            } else {
                PlayerSync.LOGGER.warn("Backpack item in slot {} has no contentsUuid", slot);
            }
            return false;
        });
    }

    /**
     * Collects Sophisticated Backpack UUIDs from the player's inventory.
     * Must be called on the MAIN THREAD (reads inventory items).
     * Also refreshes wrappers to flush in-memory state to SavedData.
     */
    /**
     * Collects Sophisticated Backpack UUIDs AND snapshots their contents on the MAIN THREAD.
     * Must be called on the MAIN THREAD (reads inventory items + BackpackStorage).
     *
     * FIX: Also scans ender chest for backpacks. Previously only main inventory was scanned,
     * so backpacks in the ender chest were never saved — causing data loss/stale contents
     * when switching servers.
     *
     * FIX: Snapshots backpack NBT data on main thread (not just UUIDs). Previously,
     * saveBackpacksByUuids read BackpackStorage on a background thread, creating a race
     * window where another player viewing the backpack could modify it between the main-thread
     * refresh and the async read — causing item duplication.
     */
    public static Map<UUID, CompoundTag> snapshotBackpackData(Player player) {
        Map<UUID, CompoundTag> data = new HashMap<>();
        if (!ModList.get().isLoaded("sophisticatedbackpacks")) return data;
        try {
            // Scan main inventory via PlayerInventoryProvider
            net.p3pp3rf1y.sophisticatedbackpacks.util.PlayerInventoryProvider.get().runOnBackpacks(player,
                    (ItemStack backpackItem, String handler, String identifier, int slot) -> {
                        snapshotSingleBackpack(backpackItem, data);
                        return false;
                    });

            // FIX: Also scan ender chest (PlayerInventoryProvider does NOT include it)
            for (int i = 0; i < player.getEnderChestInventory().getContainerSize(); i++) {
                ItemStack stack = player.getEnderChestInventory().getItem(i);
                if (stack.isEmpty()) continue;
                snapshotSingleBackpack(stack, data);
            }
        } catch (Exception e) {
            PlayerSync.LOGGER.error("Error snapshotting backpack data for player {}", player.getUUID(), e);
        }
        return data;
    }

    private static void snapshotSingleBackpack(ItemStack stack, Map<UUID, CompoundTag> data) {
        try {
            // Check if this is a backpack item
            net.minecraft.resources.ResourceLocation loc = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (loc == null || !loc.getNamespace().equals("sophisticatedbackpacks")) return;

            net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.IBackpackWrapper wrapper =
                    net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper.fromStack(stack);
            try { wrapper.refreshInventoryForInputOutput(); } catch (Exception ignored) {}
            wrapper.getContentsUuid().ifPresent(uuid -> {
                CompoundTag nbt = net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackStorage.get()
                        .getOrCreateBackpackContents(uuid);
                if (nbt != null) {
                    data.put(uuid, nbt.copy()); // .copy() to freeze the state
                }
            });
        } catch (Exception ignored) {}
    }

    /** Legacy method - collects only UUIDs without snapshotting contents. */
    public static List<UUID> collectBackpackUuids(Player player) {
        return new ArrayList<>(snapshotBackpackData(player).keySet());
    }

    /**
     * Saves pre-snapshotted backpack data to DB.
     * Can be called from a background thread (no entity access — data already captured).
     */
    public static void saveBackpackSnapshots(Map<UUID, CompoundTag> snapshots) {
        for (Map.Entry<UUID, CompoundTag> entry : snapshots.entrySet()) {
            try {
                saveStorageContents(entry.getKey(), entry.getValue());
            } catch (Exception e) {
                PlayerSync.LOGGER.error("Error saving backpack data for UUID {}", entry.getKey(), e);
            }
        }
    }

    /**
     * Saves backpack contents by UUID. Reads SavedData and writes to DB.
     * Can be called from a background thread (no entity access).
     * @deprecated Use snapshotBackpackData + saveBackpackSnapshots for thread-safe saves.
     */
    public static void saveBackpacksByUuids(List<UUID> uuids) {
        for (UUID uuid : uuids) {
            try {
                CompoundTag nbt = net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackStorage.get()
                        .getOrCreateBackpackContents(uuid);
                saveStorageContents(uuid, nbt);
            } catch (Exception e) {
                PlayerSync.LOGGER.error("Error saving backpack data for UUID {}", uuid, e);
            }
        }
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
                    // FIX P0-1: clear SS storage entry before replacing. ItemContentsStorage
                    // uses getOrCreateStorageContents which MERGE-stamps when the UUID
                    // already exists — same root cause as BackpackStorage. We try public
                    // API first, reflect-clear as fallback.
                    var store = net.p3pp3rf1y.sophisticatedstorage.block.ItemContentsStorage.get();
                    clearSSStorageContents(store, finalUuid);
                    CompoundTag fresh = nbt.copy();
                    store.setStorageContents(finalUuid, fresh);
                    PlayerSync.LOGGER.info("[restore-ss] uuid={} nbt_keys={}", finalUuid, fresh.getAllKeys().size());
                } catch (Exception e) {
                    PlayerSync.LOGGER.error("Error restoring Sophisticated Storage data for UUID {}", finalUuid, e);
                }
            });
        } catch (Exception e) {
            PlayerSync.LOGGER.error("Error restoring Sophisticated Storage item", e);
        }
    }

    /**
     * Clears a Sophisticated Storage entry (by UUID) from the ItemContentsStorage
     * SavedData. Tries public {@code removeStorageContents} first, then reflection.
     */
    private static void clearSSStorageContents(
            net.p3pp3rf1y.sophisticatedstorage.block.ItemContentsStorage store, UUID uuid) {
        try {
            // Attempt public API removal (exists in some SS versions)
            try {
                java.lang.reflect.Method m = store.getClass().getMethod("removeStorageContents", UUID.class);
                m.invoke(store, uuid);
                return;
            } catch (NoSuchMethodException nsm) {
                // Fall through to reflection map-clear
            }
            for (java.lang.reflect.Field f : store.getClass().getDeclaredFields()) {
                if (java.util.Map.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Object map = f.get(store);
                    if (map instanceof java.util.Map<?, ?> m) {
                        ((java.util.Map<Object, Object>) m).remove(uuid);
                        ((java.util.Map<Object, Object>) m).remove(uuid.toString());
                    }
                }
            }
            store.setDirty();
        } catch (Throwable t) {
            PlayerSync.LOGGER.warn("[clear-ss] unable to clear SS storage for {}: {}", uuid, t.getMessage());
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
     * Collects Sophisticated Storage item UUIDs from the player's inventory and ender chest.
     * Must be called on the MAIN THREAD (reads inventory items).
     */
    public static List<UUID> collectSSUuids(Player player) {
        List<UUID> uuids = new ArrayList<>();
        if (!ModList.get().isLoaded("sophisticatedstorage")) return uuids;
        try {
            var registryAccess = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer().registryAccess();
            // Scan main inventory
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                ItemStack stack = player.getInventory().getItem(i);
                if (stack.isEmpty() || !isSophisticatedStorageItem(stack)) continue;
                try {
                    net.p3pp3rf1y.sophisticatedstorage.item.StackStorageWrapper wrapper =
                            net.p3pp3rf1y.sophisticatedstorage.item.StackStorageWrapper.fromStack(registryAccess, stack);
                    wrapper.getContentsUuid().ifPresent(uuids::add);
                } catch (Exception ignored) {}
            }
            // Scan ender chest
            for (int i = 0; i < player.getEnderChestInventory().getContainerSize(); i++) {
                ItemStack stack = player.getEnderChestInventory().getItem(i);
                if (stack.isEmpty() || !isSophisticatedStorageItem(stack)) continue;
                try {
                    net.p3pp3rf1y.sophisticatedstorage.item.StackStorageWrapper wrapper =
                            net.p3pp3rf1y.sophisticatedstorage.item.StackStorageWrapper.fromStack(registryAccess, stack);
                    wrapper.getContentsUuid().ifPresent(uuids::add);
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            PlayerSync.LOGGER.error("Error collecting SS UUIDs for player {}", player.getUUID(), e);
        }
        return uuids;
    }

    /**
     * FIX THREAD-SAFETY (C3): Captures Sophisticated Storage CompoundTags on the MAIN
     * thread by copying the SavedData entries. Previously {@link #saveSSByUuids(List)}
     * read {@code ItemContentsStorage} directly from a background thread, racing with
     * main-thread modifications (non-thread-safe HashMap) and risking torn reads → dup.
     *
     * <p>Callers should invoke this on the main thread, then pass the returned map to
     * {@link #saveSSSnapshots(Map)} on a background thread.
     */
    public static Map<UUID, CompoundTag> snapshotSSData(List<UUID> uuids) {
        Map<UUID, CompoundTag> out = new HashMap<>();
        if (uuids == null || uuids.isEmpty() || !ModList.get().isLoaded("sophisticatedstorage")) return out;
        try {
            net.p3pp3rf1y.sophisticatedstorage.block.ItemContentsStorage store =
                    net.p3pp3rf1y.sophisticatedstorage.block.ItemContentsStorage.get();
            for (UUID uuid : uuids) {
                try {
                    CompoundTag live = store.getOrCreateStorageContents(uuid);
                    if (live != null && !live.isEmpty()) {
                        out.put(uuid, live.copy());
                    }
                } catch (Exception e) {
                    PlayerSync.LOGGER.error("Error snapshotting SS contents for UUID {}", uuid, e);
                }
            }
        } catch (Exception e) {
            PlayerSync.LOGGER.error("Error reading ItemContentsStorage for snapshot", e);
        }
        return out;
    }

    /** Background-thread writer for the frozen snapshot produced by {@link #snapshotSSData(List)}. */
    public static void saveSSSnapshots(Map<UUID, CompoundTag> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) return;
        for (Map.Entry<UUID, CompoundTag> e : snapshots.entrySet()) {
            try {
                saveStorageContents(e.getKey(), e.getValue());
            } catch (Exception ex) {
                PlayerSync.LOGGER.error("Error saving SS snapshot for UUID {}", e.getKey(), ex);
            }
        }
    }

    /**
     * @deprecated unsafe — reads ItemContentsStorage from the calling thread (possibly
     * background), racing with main-thread modifications. Use {@link #snapshotSSData(List)}
     * on main thread followed by {@link #saveSSSnapshots(Map)} on background thread.
     */
    @Deprecated
    public static void saveSSByUuids(List<UUID> uuids) {
        for (UUID uuid : uuids) {
            try {
                CompoundTag nbt = net.p3pp3rf1y.sophisticatedstorage.block.ItemContentsStorage.get()
                        .getOrCreateStorageContents(uuid);
                if (nbt != null && !nbt.isEmpty()) {
                    saveStorageContents(uuid, nbt);
                }
            } catch (Exception e) {
                PlayerSync.LOGGER.error("Error saving SS data for UUID {}", uuid, e);
            }
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

    /**
     * Saves RS2 disk storage contents by UUID using a pre-captured ServerLevel reference.
     * Can be called from a background thread (SavedData read + DB write, no entity access).
     */
    public static void saveRS2DisksByLevel(List<UUID> diskUuids, net.minecraft.server.level.ServerLevel level,
                                           net.minecraft.core.HolderLookup.Provider registryAccess) {
        if (diskUuids.isEmpty()) return;
        try {
            com.refinedmods.refinedstorage.common.api.storage.StorageRepository repo =
                    com.refinedmods.refinedstorage.common.api.RefinedStorageApi.INSTANCE.getStorageRepository(level);
            if (!(repo instanceof net.minecraft.world.level.saveddata.SavedData sd)) return;

            net.minecraft.nbt.CompoundTag fullNbt = sd.save(new net.minecraft.nbt.CompoundTag(), registryAccess);

            for (UUID uuid : diskUuids) {
                net.minecraft.nbt.CompoundTag entryNbt = findRS2EntryInNbt(fullNbt, uuid.toString());
                if (entryNbt != null && !entryNbt.isEmpty()) {
                    saveStorageContents(uuid, entryNbt);
                    PlayerSync.LOGGER.info("Saved RS2 disk data for UUID {} (async save)", uuid);
                }
            }
        } catch (Exception e) {
            PlayerSync.LOGGER.error("Error saving RS2 disks by level", e);
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
                        // FIX: storedNbt is the INNER data ({type, capacity, resources}).
                        // The map codec expects {uuid-string: {type, capacity, resources}}.
                        // Wrap the data back in a UUID-keyed CompoundTag before decoding.
                        net.minecraft.nbt.CompoundTag wrapped = new net.minecraft.nbt.CompoundTag();
                        wrapped.put(uuid.toString(), storedNbt);

                        @SuppressWarnings("unchecked")
                        com.mojang.serialization.DataResult<?> dataResult = fCodec.decode(ops, wrapped);
                        Optional<?> opt = dataResult.result();
                        if (opt.isPresent()) {
                            com.mojang.datafixers.util.Pair<?, ?> pair = (com.mojang.datafixers.util.Pair<?, ?>) opt.get();
                            @SuppressWarnings("unchecked")
                            java.util.Map<UUID, ?> decoded = (java.util.Map<UUID, ?>) pair.getFirst();
                            for (java.util.Map.Entry<UUID, ?> entry : decoded.entrySet()) {
                                // FIX: repo.set() throws IllegalArgumentException if UUID already exists.
                                // Remove first, then set. Also inject directly into the entries map
                                // via reflection as a fallback if the public API fails.
                                try {
                                    repo.remove(entry.getKey());
                                } catch (Exception ignored) {}
                                try {
                                    repo.set(entry.getKey(),
                                            (com.refinedmods.refinedstorage.common.api.storage.SerializableStorage) entry.getValue());
                                } catch (Exception setEx) {
                                    // Fallback: inject directly into the entries map
                                    PlayerSync.LOGGER.debug("repo.set() failed, using reflection fallback", setEx);
                                    try {
                                        java.lang.reflect.Field entriesField = repo.getClass().getDeclaredField("entries");
                                        entriesField.setAccessible(true);
                                        @SuppressWarnings("unchecked")
                                        java.util.Map<UUID, Object> entries = (java.util.Map<UUID, Object>) entriesField.get(repo);
                                        entries.put(entry.getKey(), entry.getValue());
                                        if (repo instanceof net.minecraft.world.level.saveddata.SavedData sdRef) {
                                            sdRef.setDirty();
                                        }
                                    } catch (Exception reflectEx) {
                                        PlayerSync.LOGGER.error("RS2 reflection fallback also failed for UUID {}", entry.getKey(), reflectEx);
                                    }
                                }
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
    public static List<UUID> collectRS2DiskUuids(Player player) {
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
