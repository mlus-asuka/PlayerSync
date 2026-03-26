package vip.fubuki.playersync.sync;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.WorldData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerNegotiationEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import vip.fubuki.playersync.PlayerSync;
import vip.fubuki.playersync.config.JdbcConfig;
import vip.fubuki.playersync.sync.addons.CuriosCache;
import vip.fubuki.playersync.sync.addons.ModCompatSync;
import vip.fubuki.playersync.sync.addons.ModsSupport;
import vip.fubuki.playersync.util.JDBCsetUp;
import vip.fubuki.playersync.util.LocalJsonUtil;
import vip.fubuki.playersync.util.PSThreadPoolFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

@EventBusSubscriber(modid = PlayerSync.MODID)
public class VanillaSync {

    public static void register() {}

    // FIX: Replace unbounded CachedThreadPool with a bounded ThreadPoolExecutor.
    // CachedThreadPool creates unlimited threads — with many players and slow DB queries,
    // thread count can explode to 25000+ causing memory leaks and server crashes.
    // Bounded pool: 2 core threads, max 8 threads, 30s keepalive, 256-task queue.
    // If the queue is full, tasks run on the calling thread (CallerRunsPolicy) which
    // provides natural backpressure instead of creating more threads.
    static ExecutorService executorService = new ThreadPoolExecutor(
            2,                          // core pool size
            8,                          // maximum pool size
            30L, TimeUnit.SECONDS,      // idle thread keepalive
            new LinkedBlockingQueue<>(256),  // bounded work queue
            new PSThreadPoolFactory("PlayerSync"),
            new ThreadPoolExecutor.CallerRunsPolicy()  // backpressure: run on caller thread if queue full
    );

    // Per-player locks to prevent concurrent save/restore operations (anti-duplication)
    private static final ConcurrentHashMap<String, ReentrantLock> playerLocks = new ConcurrentHashMap<>();

    private static ReentrantLock getPlayerLock(String uuid) {
        return playerLocks.computeIfAbsent(uuid, k -> new ReentrantLock());
    }

    public static void removePlayerLock(String uuid) {
        playerLocks.remove(uuid);
    }

    @SubscribeEvent
    public static void onDataPackSyncEvent(OnDatapackSyncEvent event) throws SQLException, IOException {
        if (!JdbcConfig.SYNC_ADVANCEMENTS.get())
            return; // advancement sync disabled

        final ServerPlayer serverPlayer = event.getPlayer();
        if (serverPlayer == null) {
            PlayerSync.LOGGER.debug("No player joining");
            return;
        }

        final String player_uuid = serverPlayer.getUUID().toString();
        PlayerSync.LOGGER.info("Player entity joining level {}", player_uuid);

        // Use try-with-resources to prevent connection leaks
        String advancementsData;
        try (JDBCsetUp.QueryResult advancementsQuery = JDBCsetUp.executePreparedQuery(
                "SELECT advancements FROM player_data WHERE uuid=?", player_uuid)) {
            ResultSet advancementsResultSet = advancementsQuery.resultSet();

            if (!advancementsResultSet.next()) {
                PlayerSync.LOGGER.debug("No advancements found for player {}", player_uuid);
                return;
            }
            advancementsData = advancementsResultSet.getString("advancements");
        }

        if (advancementsData == null || advancementsData.length() < 2) {
            PlayerSync.LOGGER.debug("Skip writing advancements for player {} (empty data)", player_uuid);
            return;
        }

        byte[] bytes = advancementsData.getBytes(StandardCharsets.UTF_8);

        // Restore Advancements
        Path path = serverPlayer.getServer().getServerDirectory().resolve(getSyncWorldForServer());
        File gameDir = path.toFile();

        final MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server.isDedicatedServer()) {
            PlayerSync.LOGGER.debug("Attempting to write dedicated server advancement file");
            File advancements = new File(gameDir,
                    "/advancements" + "/" + player_uuid + ".json");

            File advancementsDir = advancements.getParentFile();
            if (advancementsDir != null && !advancementsDir.exists()) {
                PlayerSync.LOGGER.info("Creating advancements directory {}", advancementsDir.getPath());
                boolean createdDir = advancementsDir.mkdirs();
                if (!createdDir) {
                    PlayerSync.LOGGER.error("Aborting advancements sync. Failed to create advancements directory at {}", advancementsDir.getPath());
                    return;
                }
            }

            if (!advancements.exists()) {
                try {
                    PlayerSync.LOGGER.info("Creating new advancement file for player {}", player_uuid);
                    advancements.createNewFile();
                } catch (IOException e) {
                    PlayerSync.LOGGER.error("Aborting advancements sync. Failed to create advancements file at {}", advancements.getAbsolutePath(), e);
                    return;
                }
            }
            PlayerSync.LOGGER.debug("Writing advancement file {} for player {}", advancements.toPath(), player_uuid);
            PlayerSync.LOGGER.trace("Writing advancement file for player {}: {}", player_uuid, new String(bytes, StandardCharsets.UTF_8));
            Files.write(advancements.toPath(), bytes);

            // reload the JSON files on the server after updating them
            PlayerAdvancements playeradvancements = serverPlayer.getAdvancements();
            playeradvancements.reload(server.getAdvancements());

        } else {
            PlayerSync.LOGGER.debug("Writing non-dedicated server advancement files");
            File[] files = scanAdvancementsFile(player_uuid, gameDir);
            for (File file : files) {
                if (file == null)
                    continue;
                Files.write(file.toPath(), bytes);
            }
        }
    }

    public static void doPlayerConnect(PlayerNegotiationEvent event) {
        try {
            String player_uuid = event.getProfile().getId().toString();
            PlayerSync.LOGGER.info("Detected connection from player {}, starting checking", player_uuid);
            boolean online;
            int lastServer;

            // First query: check basic player data using prepared statement
            try (JDBCsetUp.QueryResult qr1 = JDBCsetUp.executePreparedQuery(
                    "SELECT online, last_server FROM player_data WHERE uuid=?", player_uuid)) {
                ResultSet rs1 = qr1.resultSet();
                if (!rs1.next()) {
                    PlayerSync.LOGGER.info("A new-player connection detected");
                    return;
                }
                online = rs1.getBoolean("online");
                lastServer = rs1.getInt("last_server");
            }

            // Second query: Check if player is already online on another server
            if (JdbcConfig.KICK_WHEN_ALREADY_ONLINE.get() && online && lastServer != JdbcConfig.SERVER_ID.get()) {
                try (JDBCsetUp.QueryResult qr2 = JDBCsetUp.executePreparedQuery(
                        "SELECT last_update, enable FROM server_info WHERE id=?", lastServer)) {
                    ResultSet rs2 = qr2.resultSet();
                    if (rs2.next()) {
                        long last_update = rs2.getLong("last_update");
                        boolean enable = rs2.getBoolean("enable");
                        if (enable && System.currentTimeMillis() < last_update + 300000L) {
                            event.getConnection().disconnect(Component.translatableWithFallback("playersync.already_online","You can't join more than one synchronization server at the same time."));
                            return;
                        }
                        JDBCsetUp.executePreparedUpdate("UPDATE server_info SET enable=0 WHERE id=?", lastServer);
                    }
                }
            }
        } catch (Exception e) {
            PlayerSync.LOGGER.error("SqlException detected!", e);
            event.getConnection().disconnect(Component.translatableWithFallback("playersync.sqlexception","SqlException detected!Connection lost,please contact with your admin."));
        }
    }

    // Use string uuid as key
    public static Set<String> deadPlayerWhileLogging = ConcurrentHashMap.newKeySet();
    public static Set<String> syncNotCompletedPlayer = ConcurrentHashMap.newKeySet();
    // Players kicked for being already online on another server - their logout must NOT set online=0
    public static Set<String> kickedForDuplicateLogin = ConcurrentHashMap.newKeySet();

    public static void doPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        ServerPlayer serverPlayer = (ServerPlayer) event.getEntity();
        String player_uuid = serverPlayer.getUUID().toString();
        MinecraftServer server = serverPlayer.getServer();

        if (server == null) {
            PlayerSync.LOGGER.error("Server is null for player {}", player_uuid);
            return;
        }

        if (serverPlayer.isDeadOrDying()) {
            deadPlayerWhileLogging.add(player_uuid);
            serverPlayer.removeTag("player_synced");
            server.execute(() -> {
                ResourceKey<Level> respawnLevel = serverPlayer.getRespawnDimension();
                BlockPos respawnPos = serverPlayer.getRespawnPosition();
                if (respawnPos != null) {
                    ServerLevel level = server.getLevel(respawnLevel);
                    if (level != null) {
                        serverPlayer.teleportTo(level, respawnPos.getX(), respawnPos.getY() + 1, respawnPos.getZ(), 0, 0);
                    }
                }
                serverPlayer.setHealth(1);
                serverPlayer.connection.disconnect(Component.translatableWithFallback("playersync.wrong_entity_status","An error occurred while creating playerEntity in the world,please login again."));
            });
            try {
                JDBCsetUp.executePreparedUpdate("UPDATE server_info SET last_update=? WHERE id=?", System.currentTimeMillis(), JdbcConfig.SERVER_ID.get());
                JDBCsetUp.executePreparedUpdate("UPDATE player_data SET online=1, last_server=? WHERE uuid=?", JdbcConfig.SERVER_ID.get(), player_uuid);
            } catch (SQLException e) {
                PlayerSync.LOGGER.error("An error occurred while handling dead/dying player {}", e.getMessage());
            }
            return;
        }

        ReentrantLock lock = getPlayerLock(player_uuid);
        lock.lock();
        try {
            PlayerSync.LOGGER.info("Starting synchronization for player {}", player_uuid);
            syncNotCompletedPlayer.add(player_uuid);

            // === PHASE 1: DB reads on background thread (thread-safe) ===

            boolean playerExists;
            try (JDBCsetUp.QueryResult qr1 = JDBCsetUp.executePreparedQuery(
                    "SELECT uuid FROM player_data WHERE uuid=?", player_uuid)) {
                playerExists = qr1.resultSet().next();
            }

            if (!playerExists) {
                server.execute(() -> {
                    try {
                        new ModsSupport().doCuriosRestore(serverPlayer);
                        store(serverPlayer, true);
                        JDBCsetUp.executePreparedUpdate("UPDATE server_info SET last_update=? WHERE id=?", System.currentTimeMillis(), JdbcConfig.SERVER_ID.get());
                        JDBCsetUp.executePreparedUpdate("UPDATE player_data SET online=1, last_server=? WHERE uuid=?", JdbcConfig.SERVER_ID.get(), player_uuid);
                        serverPlayer.addTag("player_synced");
                    } catch (Exception e) {
                        PlayerSync.LOGGER.error("Error initializing new player {}", player_uuid, e);
                    } finally {
                        syncNotCompletedPlayer.remove(player_uuid);
                    }
                });
                return;
            }

            JDBCsetUp.executePreparedUpdate("UPDATE server_info SET last_update=? WHERE id=?", System.currentTimeMillis(), JdbcConfig.SERVER_ID.get());
            JDBCsetUp.executePreparedUpdate("UPDATE player_data SET online=1, last_server=? WHERE uuid=?", JdbcConfig.SERVER_ID.get(), player_uuid);

            // Read all DB data into local variables (background thread - safe)
            final int health, foodLevel, xp, score;
            final String leftHand, cursors, armorData, inventoryData, enderChestData, effectData;

            try (JDBCsetUp.QueryResult qr2 = JDBCsetUp.executePreparedQuery(
                    "SELECT * FROM player_data WHERE uuid=?", player_uuid)) {
                ResultSet rs2 = qr2.resultSet();
                if (!rs2.next()) {
                    PlayerSync.LOGGER.warn("No data found for existing player {}", player_uuid);
                    syncNotCompletedPlayer.remove(player_uuid);
                    return;
                }
                health = rs2.getInt("health");
                foodLevel = rs2.getInt("food_level");
                xp = rs2.getInt("xp");
                score = rs2.getInt("score");
                leftHand = rs2.getString("left_hand");
                cursors = rs2.getString("cursors");
                armorData = rs2.getString("armor");
                inventoryData = rs2.getString("inventory");
                enderChestData = rs2.getString("enderchest");
                effectData = rs2.getString("effects");
            }

            // === PHASE 2: Apply to player on MAIN SERVER THREAD ===
            // Minecraft entities are NOT thread-safe. Modifying inventory/health/effects
            // from a background thread causes duplication exploits and corruption.
            CountDownLatch applyLatch = new CountDownLatch(1);
            server.execute(() -> {
                try {
                    // ANTI-DUPLICATION: Clear all inventories BEFORE restoring
                    serverPlayer.getInventory().clearContent();
                    serverPlayer.getEnderChestInventory().clearContent();
                    serverPlayer.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
                    serverPlayer.containerMenu.setCarried(ItemStack.EMPTY);
                    for (int i = 0; i < serverPlayer.getInventory().armor.size(); i++) {
                        serverPlayer.getInventory().armor.set(i, ItemStack.EMPTY);
                    }

                    // Restore basic attributes
                    serverPlayer.setHealth(health <= 0 ? 1 : health);
                    serverPlayer.getFoodData().setFoodLevel(foodLevel);
                    setXpForPlayer(serverPlayer, xp);
                    serverPlayer.setScore(score);

                    // Restore items
                    serverPlayer.setItemInHand(InteractionHand.OFF_HAND, deserializeAndCreatePlaceholderIfNeeded(leftHand));
                    serverPlayer.containerMenu.setCarried(deserializeAndCreatePlaceholderIfNeeded(cursors));

                    if (armorData != null && armorData.length() > 2) {
                        Map<Integer, String> equipment = LocalJsonUtil.StringToEntryMap(armorData);
                        for (Map.Entry<Integer, String> entry : equipment.entrySet()) {
                            serverPlayer.getInventory().armor.set(entry.getKey(), deserializeAndCreatePlaceholderIfNeeded(entry.getValue()));
                        }
                    }
                    if (inventoryData != null && inventoryData.length() > 2) {
                        Map<Integer, String> inventory = LocalJsonUtil.StringToEntryMap(inventoryData);
                        for (Map.Entry<Integer, String> entry : inventory.entrySet()) {
                            serverPlayer.getInventory().setItem(entry.getKey(), deserializeAndCreatePlaceholderIfNeeded(entry.getValue()));
                        }
                    }
                    if (enderChestData != null && enderChestData.length() > 2) {
                        Map<Integer, String> ender_chest = LocalJsonUtil.StringToEntryMap(enderChestData);
                        for (Map.Entry<Integer, String> entry : ender_chest.entrySet()) {
                            serverPlayer.getEnderChestInventory().setItem(entry.getKey(), deserializeAndCreatePlaceholderIfNeeded(entry.getValue()));
                        }
                    }

                    // Always clear effects, then restore from DB
                    serverPlayer.removeAllEffects();
                    if (effectData != null && effectData.length() > 2) {
                        Map<Integer, String> effects = LocalJsonUtil.StringToEntryMap(effectData);
                        for (Map.Entry<Integer, String> entry : effects.entrySet()) {
                            CompoundTag effectTag = NbtUtils.snbtToStructure(deserializeString(entry.getValue()));
                            MobEffectInstance mobEffectInstance = MobEffectInstance.load(effectTag);
                            if (mobEffectInstance != null) {
                                serverPlayer.addEffect(mobEffectInstance);
                            }
                        }
                    }

                    // Restore mod data (these do their own DB reads internally, acceptable on main thread)
                    ModsSupport modsSupport = new ModsSupport();
                    modsSupport.doCuriosRestore(serverPlayer);
                    modsSupport.doBackPackRestore(serverPlayer);
                    if (ModList.get().isLoaded("sophisticatedstorage")) {
                        ModsSupport.restoreSophisticatedStorageItems(serverPlayer);
                    }
                    if (ModList.get().isLoaded("refinedstorage")) {
                        ModsSupport.restoreRefinedStorageDisks(serverPlayer);
                    }
                    ModCompatSync.restoreAll(serverPlayer);

                    serverPlayer.addTag("player_synced");
                    PlayerSync.LOGGER.info("Sync data for player {} completed.", player_uuid);
                } catch (Exception e) {
                    PlayerSync.LOGGER.error("Error applying sync data for player {}", player_uuid, e);
                } finally {
                    syncNotCompletedPlayer.remove(player_uuid);
                    applyLatch.countDown();
                }
            });

            // FIX H-3: Release lock BEFORE waiting on latch to prevent deadlock.
            // If we hold the lock while waiting, onServerShutdown trying to acquire
            // the same lock will deadlock (shutdown blocks main thread, preventing
            // server.execute() from draining, preventing latch countdown).
            lock.unlock();

            if (!applyLatch.await(15, TimeUnit.SECONDS)) {
                PlayerSync.LOGGER.error("Timeout waiting for main thread sync for player {}", player_uuid);
                syncNotCompletedPlayer.remove(player_uuid);
            }
            return; // Lock already released, skip finally
        } catch (Exception e) {
            PlayerSync.LOGGER.error("Internal Exception detected!", e);
            syncNotCompletedPlayer.remove(player_uuid);
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    @SubscribeEvent
    public static void onPlayerConnect(PlayerNegotiationEvent event) {
        // MUST run synchronously to block login until the duplicate check completes.
        // Running async allowed players to join before the kick check finished.
        try {
            doPlayerConnect(event);
        } catch (Exception e) {
            PlayerSync.LOGGER.error("Error during player connection check", e);
            event.getConnection().disconnect(Component.translatableWithFallback("playersync.sqlexception","SqlException detected!Connection lost,please contact with your admin."));
        }
    }

    /**
     * FIX: Full duplicate-login kick check during PlayerLoggedInEvent.
     * PlayerNegotiationEvent.getConnection().disconnect() does NOT reliably disconnect
     * the player in NeoForge 1.21.1. By the time PlayerLoggedInEvent fires, we have
     * a full ServerPlayer with player.connection.disconnect() which is reliable.
     *
     * Also marks online=1 SYNCHRONOUSLY to close the race condition window.
     */
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.HIGHEST)
    public static void onPlayerLoggedInKickCheck(PlayerEvent.PlayerLoggedInEvent event) {
        ServerPlayer player = (ServerPlayer) event.getEntity();
        String player_uuid = player.getUUID().toString();

        if (!JdbcConfig.KICK_WHEN_ALREADY_ONLINE.get()) {
            // Still mark online even if kick is disabled
            try {
                JDBCsetUp.executePreparedUpdate(
                        "UPDATE player_data SET online=1, last_server=? WHERE uuid=?",
                        JdbcConfig.SERVER_ID.get(), player_uuid);
            } catch (SQLException ignored) {}
            return;
        }

        try {
            boolean online = false;
            int lastServer = 0;

            try (JDBCsetUp.QueryResult qr = JDBCsetUp.executePreparedQuery(
                    "SELECT online, last_server FROM player_data WHERE uuid=?", player_uuid)) {
                ResultSet rs = qr.resultSet();
                if (rs.next()) {
                    online = rs.getBoolean("online");
                    lastServer = rs.getInt("last_server");
                }
            }

            if (online && lastServer != JdbcConfig.SERVER_ID.get()) {
                // Check if the other server is still alive
                try (JDBCsetUp.QueryResult qr2 = JDBCsetUp.executePreparedQuery(
                        "SELECT last_update, enable FROM server_info WHERE id=?", lastServer)) {
                    ResultSet rs2 = qr2.resultSet();
                    if (rs2.next()) {
                        long lastUpdate = rs2.getLong("last_update");
                        boolean enable = rs2.getBoolean("enable");
                        if (enable && System.currentTimeMillis() < lastUpdate + 300000L) {
                            // Other server is alive → KICK using ServerPlayer.connection which works reliably
                            // CRITICAL: Mark as kicked BEFORE disconnect so onPlayerLogout does NOT set online=0.
                            // Without this, the logout handler resets online=0, allowing immediate reconnect bypass.
                            kickedForDuplicateLogin.add(player_uuid);
                            PlayerSync.LOGGER.warn("Kicking player {} - already online on server {}", player_uuid, lastServer);
                            player.connection.disconnect(Component.translatableWithFallback(
                                    "playersync.already_online",
                                    "You can't join more than one synchronization server at the same time."));
                            return;
                        }
                        // Other server is dead, disable it
                        JDBCsetUp.executePreparedUpdate("UPDATE server_info SET enable=0 WHERE id=?", lastServer);
                    }
                }
            }

            // Mark online=1 SYNCHRONOUSLY
            JDBCsetUp.executePreparedUpdate(
                    "UPDATE player_data SET online=1, last_server=? WHERE uuid=?",
                    JdbcConfig.SERVER_ID.get(), player_uuid);
        } catch (Exception e) {
            PlayerSync.LOGGER.error("Error during kick check for player {}", player_uuid, e);
        }
    }

    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        executorService.submit(() -> {
            try {
                doPlayerJoin(event);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    // deserialize item and potentially create placeholders
    public static ItemStack deserializeAndCreatePlaceholderIfNeeded(String serializedNbt)
            throws CommandSyntaxException {
        if (serializedNbt == null || serializedNbt.isEmpty() || serializedNbt.equals("B64:e30=")) {
            // Check for empty NBT (Base64 encoded '{}')
            return ItemStack.EMPTY;
        }

        CompoundTag compoundTag;
        String nbtString = serializedNbt; // Will be overwritten with decoded SNBT for legacy formats

        // Try binary NBT format first (new format, avoids SNBT round-trip issues)
        if (serializedNbt.startsWith("BNBT:")) {
            try {
                compoundTag = deserializeBinaryBase64Tag(serializedNbt);
            } catch (Exception e) {
                PlayerSync.LOGGER.error("Failed to deserialize binary NBT data, skipping item.", e);
                return ItemStack.EMPTY;
            }
        } else {
            // Legacy SNBT-based deserialization (B64: or old custom format)
            nbtString = deserializeString(serializedNbt);
            try {
                compoundTag = TagParser.parseTag(nbtString);
            } catch (CommandSyntaxException e) {
                // TagParser may fail on certain 1.21.1 component SNBT formats (e.g. nested lists [[{...}]])
                // Try NbtUtils.snbtToStructure as a fallback
                PlayerSync.LOGGER.warn("TagParser.parseTag failed, trying NbtUtils.snbtToStructure fallback. SNBT: {}", nbtString);
                try {
                    compoundTag = NbtUtils.snbtToStructure(nbtString);
                } catch (CommandSyntaxException e2) {
                    PlayerSync.LOGGER.error("Both SNBT parsers failed for data: {}", nbtString);
                    throw e; // re-throw original exception
                }
            }
        }

        if (compoundTag.isEmpty() || !compoundTag.contains("id", Tag.TAG_STRING)) {
            return ItemStack.EMPTY; // Invalid or empty tag
        }

        ResourceLocation registryName = ResourceLocation.tryParse(compoundTag.getString("id"));

        if (registryName == null) {
            PlayerSync.LOGGER.warn("Failed to parse registry name from NBT: {}", nbtString);
            return ItemStack.EMPTY; // Cannot determine item type
        }

        if (BuiltInRegistries.ITEM.containsKey(registryName)) {
            // Item exists (could be vanilla or a loaded mod item), restore normally
            try {
                ItemStack restoredItem = ItemStack.parse(ServerLifecycleHooks.getCurrentServer().registryAccess(),compoundTag).get();
                // Only return the restored item if the ItemStack.of did not unexpectedly
                // return an empty item
                // Either the item is not empty, or it is empty and the original tag was also
                // empty or it was an empty inventory slot
                if (!restoredItem.isEmpty() || compoundTag.isEmpty()
                        || registryName.equals(ResourceLocation.tryParse("air"))) {
                    return restoredItem;
                }
                // ItemStack.of unexpectedly returned empty for a known, non-air item.
                PlayerSync.LOGGER.warn(
                        "ItemStack.of returned EMPTY for known item {} with NBT: {}. Creating placeholder as fallback.",
                        registryName, nbtString);
            } catch (Exception e) {
                PlayerSync.LOGGER.error(
                        "Error creating ItemStack for known item {} with NBT: {}. Creating placeholder as fallback.",
                        registryName, nbtString, e);
            }
        }

        // Create placeholder
        PlayerSync.LOGGER.debug("Item {} not found in registry. Creating placeholder.", registryName);
        ItemStack placeholder = new ItemStack(Items.PAPER);

        CompoundTag placeholderNbt = new CompoundTag();
        // Store the original serialized NBT string, not the parsed CompoundTag string
        placeholderNbt.putString("playersync:original_item_nbt", serializedNbt);
        placeholderNbt.putString("playersync:original_item_id", registryName.toString());

        // Add a unique UUID to ensure the item is unstackable
        // Stacked placerholders would be converted into a single item when restoring item
        placeholderNbt.putUUID("playersync:unique_id", UUID.randomUUID());

        CustomData.set(DataComponents.CUSTOM_DATA,placeholder, placeholderNbt);
        // Add display name and lore
        String placeholderItemTitleOverride = JdbcConfig.ITEM_PLACEHOLDER_TITLE_OVERRIDE.get();
        placeholder.set(DataComponents.ITEM_NAME,
                Component
                        .literal(!placeholderItemTitleOverride.isBlank()
                                ? placeholderItemTitleOverride
                                : Component.translatable("playersync.item_placeholder_title").getString())
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.RED).withItalic(true)));

        List<Component> loreList = new ArrayList<>();
        String placeholderItemDetails = registryName.toString();

        // add a stack size if it is available
        PlayerSync.LOGGER.warn("Item {}: {}", registryName, compoundTag);
        int placeholderItemAmount = compoundTag.getInt("Count");
        if (placeholderItemAmount > 1) {
            placeholderItemDetails = placeholderItemAmount + "x " + placeholderItemDetails;
        }

        loreList.add(
                Component.literal(placeholderItemDetails)
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY).withItalic(false)));
        // add newline
        loreList.add(Component.literal(""));

        String placeholderItemDescriptionOverride = JdbcConfig.ITEM_PLACEHOLDER_DESCRIPTION_OVERRIDE.get();
        String placeholderItemDescriptionLines = ! placeholderItemDescriptionOverride.isBlank()
                ? placeholderItemDescriptionOverride
                : Component.translatable("playersync.item_placeholder_description").getString();

        for (String descriptionLine : placeholderItemDescriptionLines.split("\n")) {
            loreList.add(
                    Component.literal(descriptionLine)
                            .setStyle(Style.EMPTY.withColor(ChatFormatting.DARK_GRAY)));
        }

        placeholder.set(DataComponents.LORE,new ItemLore(loreList));

        return placeholder;
    }

    /**
     * Deserializes a string from the database back into an NBT string.
     * Handles both the new Base64 format (prefixed with "B64:") and the old custom format.
     *
     * @param encoded The string retrieved from the database.
     * @return The deserialized NBT string.
     */
    public static String deserializeString(String encoded) {
        if (encoded.startsWith("B64:")) {
            String base64 = encoded.substring(4);
            try {
                return new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ex) {
                PlayerSync.LOGGER.error("Base64 decoding failed for data: {}", encoded, ex);
                // fallback to legacy decoding below
            }
        }
        // Legacy fallback using custom replacement
        // cleanSnbt is applied here because legacy serialization could produce stray {"":""} type markers
        // B64-decoded data must NOT be cleaned as it contains verbatim NBT from modern mods
        return LocalJsonUtil.cleanSnbt(encoded.replace("|", ",")
                .replace("^", "\"")
                .replace("<", "{")
                .replace(">", "}")
                .replace("~", "'"));
    }

    /**
     * Serializes an NBT string for database storage.
     * Uses Base64 encoding by default (prefixed with "B64:").
     * If USE_LEGACY_SERIALIZATION config is true, uses the old custom replacement format.
     *
     * @param object The NBT string to serialize.
     * @return The serialized string.
     */
    public static String serialize(String object) {
        // Check the config option for backwards compatibility during writing
        if (JdbcConfig.USE_LEGACY_SERIALIZATION.get()) {
            // Use old custom replacement logic
            return object.replace(",", "|")
                         .replace("\"", "^")
                         .replace("{", "<")
                         .replace("}", ">")
                         .replace("'", "~");
        }

        // Base64 encode with a "B64:" marker for new data
        return "B64:" + Base64.getEncoder().encodeToString(object.getBytes(StandardCharsets.UTF_8));
    }

    public static void doPlayerSaveToFile(PlayerEvent.SaveToFile event) throws SQLException, IOException {
        JDBCsetUp.executePreparedUpdate("UPDATE server_info SET last_update=? WHERE id=?", System.currentTimeMillis(), JdbcConfig.SERVER_ID.get());
        if (!event.getEntity().getTags().contains("player_synced")) return;
        store(event.getEntity(), false);
    }

    @SubscribeEvent
    public static void onPlayerSaveToFile(PlayerEvent.SaveToFile event) {
        executorService.submit(() -> {
            try {
                doPlayerSaveToFile(event);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    @SubscribeEvent
    public static void onServerShutdown(ServerStoppingEvent event) throws SQLException {
        // Save ALL online players before shutdown to prevent data loss
        // Uses ServerStoppingEvent (not ServerStoppedEvent) because players are still connected
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player.getTags().contains("player_synced") && !player.isDeadOrDying()) {
                    String puuid = player.getUUID().toString();
                    // FIX: Acquire per-player lock to prevent race with queued logout save
                    ReentrantLock lock = getPlayerLock(puuid);
                    lock.lock();
                    try {
                        store(player, false);
                        if (ModList.get().isLoaded("curios")) {
                            new ModsSupport().StoreCurios(player, false);
                        }
                        ModCompatSync.storeAll(player);
                        if (ModList.get().isLoaded("sophisticatedbackpacks")) {
                            ModsSupport.storeSophisticatedBackpacks(player);
                        }
                        if (ModList.get().isLoaded("sophisticatedstorage")) {
                            ModsSupport.storeSophisticatedStorageItems(player);
                        }
                        if (ModList.get().isLoaded("refinedstorage")) {
                            ModsSupport.storeRefinedStorageDisks(player);
                        }
                        JDBCsetUp.executePreparedUpdate("UPDATE player_data SET online=0 WHERE uuid=?", puuid);
                        PlayerSync.LOGGER.info("Saved player {} data on server shutdown", player.getUUID());
                    } catch (Exception e) {
                        PlayerSync.LOGGER.error("Error saving player {} on shutdown", player.getUUID(), e);
                    } finally {
                        lock.unlock();
                    }
                }
            }
        }
        JDBCsetUp.executePreparedUpdate("UPDATE server_info SET enable=0 WHERE id=?", JdbcConfig.SERVER_ID.get());
    }

    /**
     * FIX C-2: All save operations run on the MAIN THREAD (onPlayerLogout fires on main thread).
     * Entity state (inventory, curios, effects) is read safely on the correct thread.
     * DB writes block briefly but this is required for correctness.
     */
    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        String player_uuid = event.getEntity().getUUID().toString();
        // FIX: Players kicked for duplicate login must NOT set online=0.
        // They are still online on the OTHER server. Setting online=0 here would allow
        // them to bypass the kick by immediately reconnecting (DB says offline while
        // they're still on the other server).
        if (kickedForDuplicateLogin.contains(player_uuid)) {
            PlayerSync.LOGGER.info("Player {} was kicked for duplicate login, NOT marking offline (still on other server)", player_uuid);
            kickedForDuplicateLogin.remove(player_uuid);
            return;
        } else if (deadPlayerWhileLogging.contains(player_uuid)) {
            PlayerSync.LOGGER.warn("A dead or dying player was kicked, uuid: {}", player_uuid);
            try {
                JDBCsetUp.executePreparedUpdate("UPDATE player_data SET online=0 WHERE uuid=?", player_uuid);
            } catch (SQLException e) {
                PlayerSync.LOGGER.error("Error marking dead player offline: {}", player_uuid, e);
            }
            deadPlayerWhileLogging.remove(player_uuid);
        } else if (syncNotCompletedPlayer.contains(player_uuid)) {
            PlayerSync.LOGGER.warn("Player {} logged out with uncompleted sync. Data won't be saved for safety.", player_uuid);
            try {
                JDBCsetUp.executePreparedUpdate("UPDATE player_data SET online=0 WHERE uuid=?", player_uuid);
            } catch (SQLException e) {
                PlayerSync.LOGGER.error("Error marking unsynced player offline: {}", player_uuid, e);
            }
            syncNotCompletedPlayer.remove(player_uuid);
        } else {
            Player player = event.getEntity();
            ReentrantLock lock = getPlayerLock(player_uuid);
            lock.lock();
            try {
                // Save curios (main thread - safe to read Curios API)
                if (ModList.get().isLoaded("curios")) {
                    ModsSupport modsSupport = new ModsSupport();
                    if (player.isDeadOrDying()) {
                        modsSupport.saveCuriosFromCacheOrApi(player);
                    } else {
                        modsSupport.onPlayerLeave(player);
                    }
                }
                // Save mod compat data (main thread - safe to read Accessories/CosmeticArmor)
                ModCompatSync.storeAll(player);
                // Save main inventory + effects + advancements (main thread - safe)
                store(player, false);
                JDBCsetUp.executePreparedUpdate("UPDATE player_data SET online=0 WHERE uuid=?", player_uuid);
            } catch (Exception e) {
                PlayerSync.LOGGER.error("Error during player logout save for {}", player_uuid, e);
            } finally {
                lock.unlock();
                removePlayerLock(player_uuid);
            }
        }
    }

    // Helper function to get the NBT string to be saved
    // If item is a placeholder, get original NBT; otherwise, get current NBT
    public static String getNbtForStorage(ItemStack itemStack) {
        if (itemStack.is(Items.PAPER) && itemStack.getComponents().has(DataComponents.CUSTOM_DATA)
                && itemStack.getComponents().get(DataComponents.CUSTOM_DATA).contains("playersync:original_item_nbt")) {
            // It's our placeholder, retrieve the original NBT string
            return itemStack.getComponents().get(DataComponents.CUSTOM_DATA).copyTag().getString("playersync:original_item_nbt");
        } else {
            // It's a normal item or empty, serialize using binary NBT to avoid SNBT round-trip issues
            Tag tag = serializeNBT(itemStack);
            if (tag instanceof CompoundTag compoundTag) {
                return serializeTagToBinaryBase64(compoundTag);
            }
            // Fallback to SNBT-based serialization for non-compound tags
            return serialize(tag.toString());
        }
    }

    /**
     * Serializes a CompoundTag to a Base64-encoded binary NBT string.
     * This avoids SNBT round-trip issues where Tag.toString() produces SNBT
     * that TagParser.parseTag() cannot parse back (e.g. with nested lists [[{...}]]).
     */
    public static String serializeTagToBinaryBase64(CompoundTag tag) {
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            net.minecraft.nbt.NbtIo.writeCompressed(tag, baos);
            return "BNBT:" + Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (IOException e) {
            PlayerSync.LOGGER.error("Failed to serialize NBT to binary, falling back to SNBT", e);
            return serialize(tag.toString());
        }
    }

    /**
     * Deserializes a Base64-encoded binary NBT string back to a CompoundTag.
     */
    public static CompoundTag deserializeBinaryBase64Tag(String encoded) throws IOException {
        String base64 = encoded.substring(5); // Remove "BNBT:" prefix
        byte[] bytes = Base64.getDecoder().decode(base64);
        java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(bytes);
        return net.minecraft.nbt.NbtIo.readCompressed(bais, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
    }

    public static Tag serializeNBT(ItemStack itemStack) {
        if (itemStack == null || itemStack.isEmpty()) {
            return new CompoundTag();
        }
        // Serialize the ItemStack to NBT
        HolderLookup.Provider provider = ServerLifecycleHooks.getCurrentServer().registryAccess();
        Tag compoundTag;
        compoundTag = itemStack.save(provider);
        return compoundTag;
    }

    public static void store(Player player, boolean init) throws SQLException, IOException {
        String player_uuid = player.getUUID().toString();
        PlayerSync.LOGGER.info("Storing data for player {} (init={})", player_uuid, init);

        // Basic Attributes
        int XP = getTotalExperience(player);
        int score = player.getScore();
        int food_level = player.getFoodData().getFoodLevel();
        int health = (int) player.getHealth();
        // Left Hand
        String left_hand = getNbtForStorage(player.getItemInHand(InteractionHand.OFF_HAND));

        // Cursor
        String cursors = getNbtForStorage(player.containerMenu.getCarried());

        // Equipment (Armor)
        Map<Integer, String> equipment = new HashMap<>();
        for (int i = 0; i < player.getInventory().armor.size(); i++) {
            ItemStack itemStack = player.getInventory().armor.get(i);
            equipment.put(i, getNbtForStorage(itemStack));
        }
        // Inventory
        Inventory inventory = player.getInventory();
        Map<Integer, String> inventoryMap = new HashMap<>();
        for (int i = 0; i < inventory.items.size(); i++) {
            inventoryMap.put(i, getNbtForStorage(inventory.items.get(i)));
        }
        // Ender Chest
        Map<Integer, String> ender_chest = new HashMap<>();
        for (int i = 0; i < player.getEnderChestInventory().getContainerSize(); i++) {
            ender_chest.put(i, getNbtForStorage(player.getEnderChestInventory().getItem(i)));
        }

        if(ModList.get().isLoaded("sophisticatedbackpacks")){
            ModsSupport.storeSophisticatedBackpacks(player);
        }
        if(ModList.get().isLoaded("sophisticatedstorage")){
            ModsSupport.storeSophisticatedStorageItems(player);
        }
        if(ModList.get().isLoaded("refinedstorage")){
            ModsSupport.storeRefinedStorageDisks(player);
        }

        // Effects
        Map<Holder<MobEffect>, MobEffectInstance> effects = player.getActiveEffectsMap();
        Map<Integer, String> effectMap = new HashMap<>();
        for (Map.Entry<Holder<MobEffect>, MobEffectInstance> entry : effects.entrySet()) {
            Tag effectTag = entry.getValue().save();
            effectMap.put(BuiltInRegistries.MOB_EFFECT.getId(entry.getKey().value()), serialize(effectTag.toString()));
        }

        // Advancements
        File advancements = null;
        byte[] advancementBytes = new byte[0];
        if (JdbcConfig.SYNC_ADVANCEMENTS.get()) {
            // FIX: Force Minecraft to flush the player's advancements to disk BEFORE reading the file.
            // Without this, recently earned advancements may not be in the file yet (Minecraft only
            // flushes advancements during auto-save ~every 5 min). If the player switches servers
            // before the next auto-save, the stale file is read and new advancements are lost.
            if (player instanceof ServerPlayer sp) {
                try {
                    sp.getAdvancements().save();
                } catch (Exception e) {
                    PlayerSync.LOGGER.warn("Failed to flush advancements to disk for player {}", player_uuid, e);
                }
            }

            Path path = player.getServer().getServerDirectory().resolve(getSyncWorldForServer());
            File gameDir = path.toFile();
            final MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server != null && server.isDedicatedServer()) {
                PlayerSync.LOGGER.trace("Reading dedicated server advancements");
                advancements = new File(gameDir,"/advancements" + "/" + player_uuid + ".json");
            } else {
                gameDir = Objects.requireNonNull(player.getServer()).getServerDirectory().toFile();
                PlayerSync.LOGGER.debug("Reading non-dedicated server advancements");
                File[] files = scanAdvancementsFile(player_uuid, gameDir);
                long latestModifiedDate = 0;
                for (File file : files) {
                    if (file == null) continue;
                    if (file.lastModified() > latestModifiedDate) {
                        latestModifiedDate = file.lastModified();
                        advancements = file;
                    }
                }
            }

            // FIX: Null safety - advancements file may be null if no files were found
            if (advancements != null && advancements.exists()) {
                PlayerSync.LOGGER.debug("Storing advancements for {} from {}", player_uuid, advancements.toPath());
                advancementBytes = Files.readAllBytes(advancements.toPath());
            } else {
                PlayerSync.LOGGER.warn("Unable to save advancements for player {} (file not found)", player_uuid);
            }
        }
        String json = new String(advancementBytes, StandardCharsets.UTF_8);
        PlayerSync.LOGGER.trace("Storing advancements for player {}: {}", player_uuid, json);

        // SQL Operation for player data - using prepared statements to prevent
        // SQL injection and data corruption from special characters (especially in advancement JSON)
        if (init) {
            JDBCsetUp.executePreparedUpdate(
                    "INSERT INTO player_data (uuid, armor, inventory, enderchest, advancements, effects, xp, food_level, health, score, left_hand, cursors, online) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)",
                    player_uuid, equipment.toString(), inventoryMap.toString(), ender_chest.toString(), json, effectMap.toString(), XP, food_level, health, score, left_hand, cursors);
        } else {
            JDBCsetUp.executePreparedUpdate(
                    "UPDATE player_data SET inventory=?, armor=?, xp=?, effects=?, enderchest=?, score=?, food_level=?, health=?, advancements=?, left_hand=?, cursors=? WHERE uuid=?",
                    inventoryMap.toString(), equipment.toString(), XP, effectMap.toString(), ender_chest.toString(), score, food_level, health, json, left_hand, cursors, player_uuid);
        }
    }

    private static String getSyncWorldForServer() {
        if (!JdbcConfig.SYNC_WORLD.get().isEmpty()) {
            PlayerSync.LOGGER.warn("Using configuration 'sync_world' on servers is deprecated. Please leave the array empty. Falling back to first entry.");
            return JdbcConfig.SYNC_WORLD.get().getFirst();
        }

        final MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            PlayerSync.LOGGER.error("Unable to get current server. Assuming default level-name 'world'.");
            return "world";
        }

        final WorldData worldData = server.getWorldData();
        final String levelName = worldData.getLevelName();
        PlayerSync.LOGGER.debug("Using server level-name: {}", levelName);

        return levelName;
    }

    private static File[] scanAdvancementsFile(String player_uuid, File gameDir) {
        File[] files = new File[JdbcConfig.SYNC_WORLD.get().size()];
        for (int i = 0; i < JdbcConfig.SYNC_WORLD.get().size(); i++) {
            File advanceFile = new File(gameDir, "saves/" + JdbcConfig.SYNC_WORLD.get().get(i) + "/advancements" + "/" + player_uuid + ".json");
            if (!advanceFile.exists()) continue;
            files[i] = advanceFile;
        }
        return files;
    }

    // All periodic tasks merged into a single ServerTickEvent handler.
    // FIX: Previously used LevelTickEvent which fires once per dimension, causing the tick counter
    // to increment 3x faster than expected (once per overworld, nether, end).
    private static int heartbeatTickCounter = 0;
    private static final int HEARTBEAT_INTERVAL_TICKS = 600; // Every 30 seconds (20 tps * 30s)
    private static int autoSaveTickCounter = 0;
    private static final int AUTO_SAVE_INTERVAL_TICKS = 1200; // Every minute
    private static int autoCleanCuriosCacheTickCounter = 0;
    private static final int AUTO_CLEAN_CURIOS_CACHE_INTERVAL_TICKS = 36000; // Every 30 min

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        heartbeatTickCounter++;
        autoSaveTickCounter++;
        autoCleanCuriosCacheTickCounter++;

        // Heartbeat: update server_info to prove this server is alive
        if (heartbeatTickCounter >= HEARTBEAT_INTERVAL_TICKS) {
            heartbeatTickCounter = 0;
            executorService.submit(() -> {
                try {
                    JDBCsetUp.executePreparedUpdate("UPDATE server_info SET last_update=? WHERE id=?",
                            System.currentTimeMillis(), JdbcConfig.SERVER_ID.get());
                } catch (SQLException e) {
                    PlayerSync.LOGGER.error("Error updating server heartbeat", e);
                }
            });
        }

        // Auto-save all online players
        // FIX C-1/C-2/C-4: onServerTick runs on the MAIN THREAD. We call store() and mod saves
        // directly here to safely read entity state (inventory, curios, effects, etc.).
        // The DB writes inside store() block briefly (~1-5ms per player) but this is acceptable
        // for a 60-second interval. This eliminates all off-thread entity access duplication exploits.
        if (autoSaveTickCounter >= AUTO_SAVE_INTERVAL_TICKS) {
            autoSaveTickCounter = 0;
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    if (player.isDeadOrDying() || syncNotCompletedPlayer.contains(player.getUUID().toString())) {
                        continue;
                    }
                    String puuid = player.getUUID().toString();
                    ReentrantLock lock = getPlayerLock(puuid);
                    if (!lock.tryLock()) continue; // Skip if already being saved (logout in progress)
                    try {
                        store(player, false);
                        if (ModList.get().isLoaded("curios") && !player.isDeadOrDying()) {
                            new ModsSupport().StoreCurios(player, false);
                        }
                        if (!player.isDeadOrDying()) {
                            ModCompatSync.storeAll(player);
                        }
                    } catch (Exception e) {
                        PlayerSync.LOGGER.error("Error auto-saving player {}", player.getUUID(), e);
                    } finally {
                        lock.unlock();
                    }
                }
            }
        }

        // Clean expired curios cache
        if (autoCleanCuriosCacheTickCounter >= AUTO_CLEAN_CURIOS_CACHE_INTERVAL_TICKS) {
            autoCleanCuriosCacheTickCounter = 0;
            executorService.submit(() -> {
                try {
                    CuriosCache.RemoveExpiredCuriosCache();
                } catch (Exception e) {
                    PlayerSync.LOGGER.error("An error occurred while cleaning curios cache: {}", e.getMessage());
                }
            });
        }
    }

    private static void setXpForPlayer(ServerPlayer serverPlayer, int databaseXp) {
        // Don't use giveExperience() as it has several side-effects:
        // triggers an event, sends network packets, increases the score, ...
        serverPlayer.totalExperience = databaseXp;
        serverPlayer.experienceLevel = 0;
        serverPlayer.experienceProgress = 0;

        int xpForLevel;

        while (databaseXp >= (xpForLevel = serverPlayer.getXpNeededForNextLevel())) {
            databaseXp -= xpForLevel;
            serverPlayer.experienceLevel++;
        }

        serverPlayer.experienceProgress = serverPlayer.experienceLevel > 0
                ? (float) databaseXp / serverPlayer.getXpNeededForNextLevel()
                : 0f;

        PlayerSync.LOGGER.debug("Giving player {} levels and {}% experience progress, calculated from {} XP.", serverPlayer.experienceLevel, serverPlayer.experienceProgress * 100, serverPlayer.totalExperience);
    }

    private static int getTotalExperience(final Player player) {
        int level = player.experienceLevel;
        int totalXp = 0;

        // Calculate total XP for completed levels
        if (level > 30) {
            totalXp = (int) (4.5 * Math.pow(level, 2) - 162.5 * level + 2220);
        } else if (level > 15) {
            totalXp = (int) (2.5 * Math.pow(level, 2) - 40.5 * level + 360);
        } else {
            totalXp = level * level + 6 * level;
        }

        // Add partial level progress
        totalXp += Math.round(player.getXpNeededForNextLevel() * player.experienceProgress);

        PlayerSync.LOGGER.debug("Experience calcuation for {} levels and {}% experience progress yields {} XP.", player.experienceLevel, player.experienceProgress * 100, totalXp);

        return totalXp;
    }

    @SubscribeEvent
    //Don't know what will happen if a fake player is killed,need more test.
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !deadPlayerWhileLogging.contains(event.getEntity().getUUID().toString())) {
            CuriosCache.tryStoreCuriosToCache(player);
        }
    }
}