package vip.fubuki.playersync.sync;

import vip.fubuki.playersync.util.SyncLogger;
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
import vip.fubuki.playersync.util.Tables;

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
    // FIX PERF: Increased pool sizing for 35+ player servers.
    // Old: 2-8 threads, 256 queue → CallerRunsPolicy caused main thread to execute
    // DB tasks when queue was full (35 auto-save tasks overflowed 256 queue → TPS drop to <1).
    // New: 4-16 threads, 512 queue → handles 35+ concurrent saves without overflow.
    static ExecutorService executorService = new ThreadPoolExecutor(
            4,                          // core pool size (was 2)
            16,                         // maximum pool size (was 8)
            30L, TimeUnit.SECONDS,      // idle thread keepalive
            new LinkedBlockingQueue<>(512),  // bounded work queue (was 256)
            new PSThreadPoolFactory("PlayerSync"),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    // Per-player locks to prevent concurrent save/restore operations (anti-duplication)
    private static final ConcurrentHashMap<String, ReentrantLock> playerLocks = new ConcurrentHashMap<>();

    // FIX: Track in-progress logout saves so doPlayerJoin can wait for them.
    // Without this, a fast disconnect+reconnect can read stale DB data while the
    // previous session's save is still in flight.
    private static final ConcurrentHashMap<String, CompletableFuture<Void>> pendingLogoutSaves = new ConcurrentHashMap<>();

    private static ReentrantLock getPlayerLock(String uuid) {
        return playerLocks.computeIfAbsent(uuid, k -> new ReentrantLock());
    }

    /**
     * FIX P1-3: returns true if the given peer server's heartbeat is missing or
     * older than {@code staleAfterMs}. Used by doPlayerJoin's last_server poll to
     * short-circuit when the peer is a zombie (crashed without clearing online flag,
     * or legacy server_id=0 from pre-fix DB rows).
     */
    private static boolean isPeerServerStale(int peerServerId, long staleAfterMs) {
        if (peerServerId == 0) return true; // 0 is never a legitimate SERVER_ID
        try (JDBCsetUp.QueryResult qr = JDBCsetUp.executePreparedQuery(
                "SELECT last_update FROM " + Tables.serverInfo() + " WHERE id=?", peerServerId)) {
            ResultSet rs = qr.resultSet();
            if (!rs.next()) return true; // no heartbeat row => dead
            long lastUpdate = rs.getLong("last_update");
            long age = System.currentTimeMillis() - lastUpdate;
            return age > staleAfterMs;
        } catch (Exception e) {
            PlayerSync.LOGGER.warn("isPeerServerStale query failed for server {}: {}", peerServerId, e.getMessage());
            return false; // err on the side of waiting
        }
    }

    public static void removePlayerLock(String uuid) {
        playerLocks.remove(uuid);
    }

    /**
     * Checks if a player is still in the server's online player list.
     * Used to avoid applying sync data to a player entity that already disconnected.
     */
    private static boolean isPlayerOnline(MinecraftServer server, String uuid) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.getUUID().toString().equals(uuid)) return true;
        }
        return false;
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
                "SELECT advancements FROM " + Tables.playerData() + " WHERE uuid=?", player_uuid)) {
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
                    "SELECT online, last_server FROM " + Tables.playerData() + " WHERE uuid=?", player_uuid)) {
                ResultSet rs1 = qr1.resultSet();
                if (!rs1.next()) {
                    PlayerSync.LOGGER.info("A new-player connection detected");
                    connectCheckCache.put(player_uuid, new int[]{0, 0, 0, 0}); // new player
                    return;
                }
                online = rs1.getBoolean("online");
                lastServer = rs1.getInt("last_server");
            }

            // Second query: Check if player is already online on another server
            int serverAlive = 0;
            int alreadyKicked = 0;
            if (JdbcConfig.KICK_WHEN_ALREADY_ONLINE.get() && online && lastServer != JdbcConfig.SERVER_ID.get()) {
                try (JDBCsetUp.QueryResult qr2 = JDBCsetUp.executePreparedQuery(
                        "SELECT last_update, enable FROM " + Tables.serverInfo() + " WHERE id=?", lastServer)) {
                    ResultSet rs2 = qr2.resultSet();
                    if (rs2.next()) {
                        long last_update = rs2.getLong("last_update");
                        boolean enable = rs2.getBoolean("enable");
                        if (enable && System.currentTimeMillis() < last_update + 300000L) {
                            serverAlive = 1;
                            event.getConnection().disconnect(Component.translatableWithFallback("playersync.already_online","You can't join more than one synchronization server at the same time."));
                            alreadyKicked = 1;
                        } else {
                            JDBCsetUp.executePreparedUpdate("UPDATE " + Tables.serverInfo() + " SET enable=0 WHERE id=?", lastServer);
                        }
                    }
                }
            }

            // FIX PERF: Cache the result for onPlayerLoggedInKickCheck (avoids re-querying on main thread)
            connectCheckCache.put(player_uuid, new int[]{online ? 1 : 0, lastServer, serverAlive, alreadyKicked});
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

    // FIX PERF: Cache from doPlayerConnect (network thread) for onPlayerLoggedInKickCheck (main thread).
    // Eliminates 2-4 redundant DB queries per join on the main thread.
    // Entry: uuid → {online, lastServer, serverAlive, alreadyHandled}
    private static final ConcurrentHashMap<String, int[]> connectCheckCache = new ConcurrentHashMap<>();
    // int[0]=online(0/1), int[1]=lastServer, int[2]=serverAlive(0/1), int[3]=alreadyKicked(0/1)

    public static void doPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        ServerPlayer serverPlayer = (ServerPlayer) event.getEntity();
        String player_uuid = serverPlayer.getUUID().toString();
        MinecraftServer server = serverPlayer.getServer();

        if (server == null) {
            PlayerSync.LOGGER.error("Server is null for player {}", player_uuid);
            syncNotCompletedPlayer.remove(player_uuid);
            return;
        }

        // FIX: If the player entity spawned dead/dying, kick+respawn them.
        // All entity modifications (removeTag, teleport, disconnect) are scheduled on the
        // main thread — the old code called removeTag from this background thread which is unsafe.
        // FIX: ReviveMe compatibility — check if the player is in a "downed" state (not truly dead).
        // ReviveMe cancels LivingDeathEvent and puts players at low health with special effects.
        // These players have health > 0 and should NOT be kicked. Only kick if actually dead (health <= 0).
        if (serverPlayer.isDeadOrDying() && serverPlayer.getHealth() <= 0) {
            deadPlayerWhileLogging.add(player_uuid);
            server.execute(() -> {
                serverPlayer.removeTag("player_synced");
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
            // online=1 already set by onPlayerLoggedInKickCheck — no duplicate DB write here
            return;
        }

        // FIX ANTI-DUPLICATION: Wait for any pending logout save from a previous session
        // on THIS server. Without this, a fast disconnect+reconnect reads stale DB data
        // while the previous session's async save is still in flight.
        CompletableFuture<Void> pendingSave = pendingLogoutSaves.get(player_uuid);
        if (pendingSave != null) {
            PlayerSync.LOGGER.info("Waiting for pending logout save to complete for player {}", player_uuid);
            try {
                pendingSave.get(15, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                PlayerSync.LOGGER.error("Timeout waiting for pending logout save for player {}", player_uuid);
            } catch (Exception e) {
                PlayerSync.LOGGER.warn("Pending logout save failed for player {}", player_uuid, e);
            }
        }

        ReentrantLock lock = getPlayerLock(player_uuid);
        lock.lock();
        try {
            PlayerSync.LOGGER.info("Starting synchronization for player {}", player_uuid);
            SyncLogger.restoreStarted(player_uuid);

            // FIX ANTI-DUPLICATION: Wait for the PREVIOUS server to finish saving this player's data.
            // The old server's writeSnapshotToDB uses AND last_server=? — once we claim last_server,
            // the old server's write is blocked. So we must wait BEFORE claiming.
            //
            // The poll checks: if last_server != this server, the old server's save may still
            // be in flight. Wait for it to set online=0 (which happens atomically with the data
            // write via the combined UPDATE). Once online=0, the data is guaranteed fresh.
            //
            // NOTE: onPlayerLoggedInKickCheck deliberately does NOT set last_server — only online=1.
            // This keeps last_server pointing to the old server so this poll can detect it.
            // FIX P1-3: raised max attempts 60→120 (30s→60s) to cover slow-shutdown peers
            // + added server_info freshness short-circuit: if the other server hasn't
            // heartbeated in >60s, treat it as dead and stop waiting immediately.
            // This fixes the user-reported "attempt 60/60" log flood for server_id=0
            // and zombie server_ids whose player_data.last_server never gets cleared.
            final int MAX_POLL = 120;
            final long STALE_HEARTBEAT_MS = 60_000L;
            for (int attempt = 0; attempt < MAX_POLL; attempt++) {
                try (JDBCsetUp.QueryResult qrCheck = JDBCsetUp.executePreparedQuery(
                        "SELECT online, last_server FROM " + Tables.playerData() + " WHERE uuid=?", player_uuid)) {
                    ResultSet rsCheck = qrCheck.resultSet();
                    if (!rsCheck.next()) break; // new player, nothing pending
                    int otherServer = rsCheck.getInt("last_server");
                    if (otherServer != JdbcConfig.SERVER_ID.get()) {
                        boolean otherOnline = rsCheck.getBoolean("online");
                        if (otherOnline) {
                            // FIX P1-3: zombie-server short-circuit. server_id=0 is never
                            // a legitimate server (SERVER_ID config generates nextInt(1, MAX-1)).
                            // Absent or stale (>60s) heartbeat => treat as dead, take over.
                            if (otherServer == 0 || isPeerServerStale(otherServer, STALE_HEARTBEAT_MS)) {
                                SyncLogger.raceCondition(player_uuid,
                                        "Peer server " + otherServer + " is dead/zombie — taking over after " + attempt + " attempts");
                                PlayerSync.LOGGER.warn("Player {} last_server={} is dead/zombie, bypassing wait",
                                        player_uuid, otherServer);
                                // Force-clear its online flag so subsequent logic proceeds cleanly.
                                JDBCsetUp.executePreparedUpdate(
                                        "UPDATE " + Tables.playerData() + " SET online=0 WHERE uuid=? AND last_server=?",
                                        player_uuid, otherServer);
                                break;
                            }
                            SyncLogger.raceCondition(player_uuid, "Waiting for server " + otherServer + " to finish saving (attempt " + (attempt + 1) + "/" + MAX_POLL + ")");
                            PlayerSync.LOGGER.info("Player {} still being saved on server {} (attempt {}/{}), waiting 500ms...",
                                    player_uuid, otherServer, attempt + 1, MAX_POLL);
                            Thread.sleep(500);
                            continue;
                        }
                    }
                }
                break; // Ready to load — other server finished or same server
            }

            // NOW claim last_server for this server — AFTER the old server's save completed.
            // This is safe because: (1) the old server's data+online=0 write already completed,
            // (2) any future writes from the old server will be blocked by AND last_server=?.
            JDBCsetUp.executePreparedUpdate(
                    "UPDATE " + Tables.playerData() + " SET last_server=? WHERE uuid=?",
                    JdbcConfig.SERVER_ID.get(), player_uuid);

            // === PHASE 1: DB reads on background thread (thread-safe) ===

            boolean playerExists;
            try (JDBCsetUp.QueryResult qr1 = JDBCsetUp.executePreparedQuery(
                    "SELECT uuid FROM " + Tables.playerData() + " WHERE uuid=?", player_uuid)) {
                playerExists = qr1.resultSet().next();
            }

            if (!playerExists) {
                server.execute(() -> {
                    if (!isPlayerOnline(server, player_uuid)) {
                        syncNotCompletedPlayer.remove(player_uuid);
                        return;
                    }
                    try {
                        new ModsSupport().doCuriosRestore(serverPlayer);
                        store(serverPlayer, true);
                        serverPlayer.addTag("player_synced");
                    } catch (Exception e) {
                        PlayerSync.LOGGER.error("Error initializing new player {}", player_uuid, e);
                    } finally {
                        syncNotCompletedPlayer.remove(player_uuid);
                    }
                });
                return;
            }

            // Read all DB data into local variables (background thread - safe)
            final int health, foodLevel, xp, score;
            final String leftHand, cursors, armorData, inventoryData, enderChestData, effectData;

            try (JDBCsetUp.QueryResult qr2 = JDBCsetUp.executePreparedQuery(
                    "SELECT * FROM " + Tables.playerData() + " WHERE uuid=?", player_uuid)) {
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

            // Pre-read ALL mod data on BACKGROUND THREAD (no entity access).
            final String curiosData;
            if (ModList.get().isLoaded("curios")) {
                try (JDBCsetUp.QueryResult qr = JDBCsetUp.executePreparedQuery(
                        "SELECT curios_item FROM " + Tables.curios() + " WHERE uuid=?", player_uuid)) {
                    ResultSet rs = qr.resultSet();
                    curiosData = rs.next() ? rs.getString("curios_item") : null;
                }
            } else { curiosData = null; }

            final String accessoriesData;
            if (ModList.get().isLoaded("accessories")) {
                try (JDBCsetUp.QueryResult qr = JDBCsetUp.executePreparedQuery(
                        "SELECT data_value FROM " + Tables.modPlayerData() + " WHERE uuid=? AND mod_id=?",
                        player_uuid, "accessories")) {
                    ResultSet rs = qr.resultSet();
                    accessoriesData = rs.next() ? rs.getString("data_value") : null;
                }
            } else { accessoriesData = null; }

            final String cosmeticArmorData;
            if (ModList.get().isLoaded("cosmeticarmorreworked")) {
                try (JDBCsetUp.QueryResult qr = JDBCsetUp.executePreparedQuery(
                        "SELECT data_value FROM " + Tables.modPlayerData() + " WHERE uuid=? AND mod_id=?",
                        player_uuid, "cosmeticarmor")) {
                    ResultSet rs = qr.resultSet();
                    cosmeticArmorData = rs.next() ? rs.getString("data_value") : null;
                }
            } else { cosmeticArmorData = null; }

            final String attachmentsData;
            try (JDBCsetUp.QueryResult qr = JDBCsetUp.executePreparedQuery(
                    "SELECT data_value FROM " + Tables.modPlayerData() + " WHERE uuid=? AND mod_id=?",
                    player_uuid, "neoforge_attachments")) {
                ResultSet rs = qr.resultSet();
                attachmentsData = rs.next() ? rs.getString("data_value") : null;
            }

            // === PHASE 2: Apply to player on MAIN SERVER THREAD ===
            // The server.execute() callback fires when the main thread is ready.
            // Note: Backpack/SS/RS2 restore still does DB reads on main thread (1-5 queries
            // per player). This is acceptable because players join one at a time, not 35 at once.
            // The real performance fix is staggering the auto-save (see onServerTick).
            server.execute(() -> {
                try {
                    // FIX: Verify the player is still connected before applying data.
                    // If the player disconnected quickly, the entity is stale and modifying
                    // it could interfere with the logout save or corrupt state.
                    if (!isPlayerOnline(server, player_uuid)) {
                        PlayerSync.LOGGER.warn("Player {} disconnected before sync apply, skipping", player_uuid);
                        SyncLogger.dataLoss(player_uuid, "Player disconnected before sync apply — .dat data may persist, DB data not applied");
                        return;
                    }

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

                    // Apply mod data from pre-read strings (NO DB calls on main thread).
                    ModsSupport.applyCuriosFromData(serverPlayer, curiosData);
                    ModCompatSync.applyAccessoriesFromData(serverPlayer, accessoriesData);
                    ModCompatSync.applyCosmeticArmorFromData(serverPlayer, cosmeticArmorData);
                    ModCompatSync.applyAttachmentsFromData(serverPlayer, attachmentsData);

                    // Backpacks/SS/RS2: need inventory items to know UUIDs, so DB reads
                    // happen here (1-5 fast queries per player, acceptable with HikariCP).
                    new ModsSupport().doBackPackRestore(serverPlayer);
                    if (ModList.get().isLoaded("sophisticatedstorage")) {
                        ModsSupport.restoreSophisticatedStorageItems(serverPlayer);
                    }
                    if (ModList.get().isLoaded("refinedstorage")) {
                        ModsSupport.restoreRefinedStorageDisks(serverPlayer);
                    }

                    serverPlayer.addTag("player_synced");
                    PlayerSync.LOGGER.info("Sync data for player {} completed.", player_uuid);
                    SyncLogger.restoreCompleted(player_uuid, 0);
                } catch (Exception e) {
                    PlayerSync.LOGGER.error("Error applying sync data for player {}", player_uuid, e);
                } finally {
                    syncNotCompletedPlayer.remove(player_uuid);
                }
            });

        } catch (Exception e) {
            PlayerSync.LOGGER.error("Internal Exception detected!", e);
            syncNotCompletedPlayer.remove(player_uuid);
            removePlayerLock(player_uuid); // FIX: prevent playerLocks memory leak on exception
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

        // FIX PERF: Use cached data from doPlayerConnect (network thread) instead of
        // re-querying the DB. Eliminates 2-4 blocking DB queries from the MAIN THREAD.
        // doPlayerConnect already ran the same checks on the network thread and cached results.
        int[] cached = connectCheckCache.remove(player_uuid);

        if (!JdbcConfig.KICK_WHEN_ALREADY_ONLINE.get()) {
            // FIX PERF (C1): online=1 is fire-and-forget; no login-critical decision depends
            // on the write completing synchronously. Keeping this off the main thread saves
            // one MySQL round-trip per join.
            executorService.execute(() -> {
                try {
                    JDBCsetUp.executePreparedUpdate(
                            "UPDATE " + Tables.playerData() + " SET online=1 WHERE uuid=?", player_uuid);
                } catch (SQLException ignored) {}
            });
            return;
        }

        try {
            if (cached != null && cached[3] == 1) {
                // doPlayerConnect already determined this player should be kicked (server alive)
                // but PlayerNegotiationEvent.disconnect() is unreliable in NeoForge 1.21.1
                // — use the reliable ServerPlayer.connection.disconnect() instead.
                kickedForDuplicateLogin.add(player_uuid);
                PlayerSync.LOGGER.warn("Kicking player {} - already online on server {} (cached check)", player_uuid, cached[1]);
                player.connection.disconnect(Component.translatableWithFallback(
                        "playersync.already_online",
                        "You can't join more than one synchronization server at the same time."));
                return;
            }

            if (cached != null && cached[0] == 1 && cached[1] != JdbcConfig.SERVER_ID.get() && cached[2] == 0) {
                // Player was online on another server but that server is dead — already handled
                // by doPlayerConnect (server disabled). No need to re-query.
            } else if (cached == null) {
                // No cache (race condition or cache eviction) — fall back to DB query
                boolean online = false;
                int lastServer = 0;
                try (JDBCsetUp.QueryResult qr = JDBCsetUp.executePreparedQuery(
                        "SELECT online, last_server FROM " + Tables.playerData() + " WHERE uuid=?", player_uuid)) {
                    ResultSet rs = qr.resultSet();
                    if (rs.next()) {
                        online = rs.getBoolean("online");
                        lastServer = rs.getInt("last_server");
                    }
                }
                if (online && lastServer != JdbcConfig.SERVER_ID.get()) {
                    try (JDBCsetUp.QueryResult qr2 = JDBCsetUp.executePreparedQuery(
                            "SELECT last_update, enable FROM " + Tables.serverInfo() + " WHERE id=?", lastServer)) {
                        ResultSet rs2 = qr2.resultSet();
                        if (rs2.next()) {
                            long lastUpdate = rs2.getLong("last_update");
                            boolean enable = rs2.getBoolean("enable");
                            if (enable && System.currentTimeMillis() < lastUpdate + 300000L) {
                                kickedForDuplicateLogin.add(player_uuid);
                                player.connection.disconnect(Component.translatableWithFallback(
                                        "playersync.already_online",
                                        "You can't join more than one synchronization server at the same time."));
                                return;
                            }
                            JDBCsetUp.executePreparedUpdate("UPDATE " + Tables.serverInfo() + " SET enable=0 WHERE id=?", lastServer);
                        }
                    }
                }
            }

            // FIX PERF (C1): Mark online=1 asynchronously — no main-thread MySQL round-trip.
            // The cache-based kick decision above is already final; this write only updates
            // the persistent flag for cross-server detection, which tolerates a few ms of delay.
            executorService.execute(() -> {
                try {
                    JDBCsetUp.executePreparedUpdate(
                            "UPDATE " + Tables.playerData() + " SET online=1 WHERE uuid=?", player_uuid);
                } catch (SQLException e) {
                    PlayerSync.LOGGER.error("Async online=1 update failed for {}", player_uuid, e);
                }
            });
        } catch (Exception e) {
            PlayerSync.LOGGER.error("Error during kick check for player {}", player_uuid, e);
        }
    }

    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        String puuid = ((ServerPlayer) event.getEntity()).getUUID().toString();

        // FIX: Don't start sync for players that were already kicked by onPlayerLoggedInKickCheck.
        // Without this, doPlayerJoin runs on a background thread for a kicked player, wastes
        // resources, and leaves stale entries in syncNotCompletedPlayer / playerLocks.
        if (kickedForDuplicateLogin.contains(puuid)) return;

        // Mark sync as pending BEFORE submitting to thread pool.
        syncNotCompletedPlayer.add(puuid);
        executorService.submit(() -> {
            try {
                doPlayerJoin(event);
            } catch (Exception e) {
                e.printStackTrace();
                syncNotCompletedPlayer.remove(puuid);
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

    /**
     * FIX CRITICAL (performance): PlayerEvent.SaveToFile fires on the MAIN THREAD
     * during Minecraft's own autosave cycle (every 6000 ticks) and on player logout.
     * The previous implementation called store() synchronously, which includes:
     *   - Full inventory serialization
     *   - Multiple JDBC UPDATE/INSERT statements (each one a synchronous network round-trip
     *     to MySQL — 5ms to 4846ms depending on network latency)
     * With 35 players this caused MSPT spikes of up to 4846ms (97× the 50ms limit).
     *
     * NEW APPROACH:
     *   1. Update server heartbeat ASYNCHRONOUSLY (no main-thread DB call).
     *   2. If the player has been synced, snapshot all entity state on the main thread
     *      (fast — pure memory serialization, no I/O).
     *   3. Submit all DB writes to the background executor thread pool.
     *   4. The main thread NEVER waits for MySQL — it returns immediately.
     *
     * Safety: backpack / SophisticatedStorage / RS2 contents are NOT saved here
     * (they are saved completely on logout and shutdown, which is the correct moment).
     * The snapshot covers inventory, effects, XP, curios, accessories, cosmetic armor,
     * and NeoForge attachments — everything that changes frequently during gameplay.
     */
    @SubscribeEvent
    public static void onPlayerSaveToFile(PlayerEvent.SaveToFile event) {
        snapshotAndQueueSave(event.getEntity(), "SaveToFile");
    }

    /**
     * Phase 4: optional save on dimension change — gated by
     * {@code save_on_dimension_change} config. Protects against mid-teleport
     * crashes when the player is about to serialize into a new world file.
     */
    @SubscribeEvent
    public static void onPlayerChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        try {
            if (!JdbcConfig.SAVE_ON_DIMENSION_CHANGE.get()) return;
            PlayerSync.LOGGER.debug("[dimension-change] queuing save for {} ({} -> {})",
                    event.getEntity().getUUID(), event.getFrom().location(), event.getTo().location());
            SyncLogger.playerEvent(event.getEntity().getUUID().toString(), "DIMENSION_CHANGE",
                    event.getFrom().location() + " -> " + event.getTo().location());
            snapshotAndQueueSave(event.getEntity(), "DIMENSION");
        } catch (Exception e) {
            PlayerSync.LOGGER.warn("[dimension-change] save trigger failed: {}", e.getMessage());
        }
    }

    /**
     * Phase 4: public entry point used by PeriodicSaveService and dimension-change
     * handler. Snapshots on main thread, queues async DB write with the full P0
     * guard stack (pendingLogoutSaves + online=0 + bgLock tryLock).
     *
     * @param player the player to snapshot — MUST be called on the server main thread
     * @param label  a short tag used in log lines for diagnosis (e.g. "SaveToFile",
     *               "PERIODIC", "DIMENSION")
     */
    public static void snapshotAndQueueSave(Player player, String label) {
        // Heartbeat piggyback — cheap, keeps server_info fresh even if no SaveToFile ticks.
        executorService.submit(() -> {
            try {
                JDBCsetUp.executePreparedUpdate("UPDATE " + Tables.serverInfo() + " SET last_update=? WHERE id=?",
                        System.currentTimeMillis(), JdbcConfig.SERVER_ID.get());
            } catch (SQLException e) {
                PlayerSync.LOGGER.error("Error updating server heartbeat on {}", label, e);
            }
        });

        String puuid = player.getUUID().toString();

        if (!player.getTags().contains("player_synced")) return;
        if (syncNotCompletedPlayer.contains(puuid)) return;
        if (player.isDeadOrDying()) return;
        // FIX: Skip if a logout save is already in flight for this player.
        // Without this, the SaveToFile background task could overwrite the fresher
        // logout snapshot with a stale one if it runs after the logout save.
        if (pendingLogoutSaves.containsKey(puuid)) return;

        // Use tryLock: if a logout save or another SaveToFile save is already writing
        // this player's data, skip — the other operation already has fresh data.
        ReentrantLock lock = getPlayerLock(puuid);
        if (!lock.tryLock()) return;

        try {
            // === MAIN THREAD: snapshot all entity state (no DB I/O, pure memory ops) ===
            final PlayerDataSnapshot snapshot = snapshotPlayerData(player);

            // === BACKGROUND THREAD: all DB writes — main thread continues immediately ===
            executorService.submit(() -> {
                // FIX: If the player already logged out (removePlayerLock was called),
                // this snapshot is stale and must NOT overwrite the fresher logout snapshot.
                if (!playerLocks.containsKey(puuid)) return;
                // FIX CRITICAL ANTI-DUP (P0-a): early skip if logout is already in flight.
                if (pendingLogoutSaves.containsKey(puuid)) return;

                ReentrantLock bgLock = getPlayerLock(puuid);
                if (!bgLock.tryLock()) return; // another save started, skip
                try {
                    // FIX CRITICAL ANTI-DUP (P0-b): re-check under lock — a logout task may
                    // have been submitted between the check above and tryLock success.
                    if (pendingLogoutSaves.containsKey(puuid)) return;
                    // FIX CRITICAL ANTI-DUP (P0-c): last line of defence — if the DB already
                    // shows online=0, a logout save has committed and any write here would
                    // resurrect stale data (cause of drop+deco+reco item duplication).
                    try (JDBCsetUp.QueryResult onlineCheck = JDBCsetUp.executePreparedQuery(
                            "SELECT online FROM " + Tables.playerData() + " WHERE uuid=?", puuid)) {
                        ResultSet rs = onlineCheck.resultSet();
                        if (rs.next() && rs.getInt("online") == 0) {
                            SyncLogger.guardBlocked(puuid, JdbcConfig.SERVER_ID.get(),
                                    "SaveToFile BG skipped — player already offline in DB (logout committed)");
                            return;
                        }
                    }
                    writeSnapshotToDB(snapshot);
                } catch (Exception e) {
                    PlayerSync.LOGGER.error("Error writing async SaveToFile snapshot for player {}", puuid, e);
                } finally {
                    bgLock.unlock();
                }
            });

        } catch (Exception e) {
            PlayerSync.LOGGER.error("Error snapshotting player {} for SaveToFile", puuid, e);
        } finally {
            lock.unlock(); // main thread releases → background thread can now acquire
        }
    }

    @SubscribeEvent
    public static void onServerShutdown(ServerStoppingEvent event) throws SQLException {
        // FIX PERF: Snapshot ALL players on main thread (fast, no DB I/O), then write
        // ALL saves in PARALLEL on background threads. Previously this was sequential:
        // 35 players × 200ms = 7 seconds blocking the main thread → watchdog "server thread stuck".
        // Now: snapshot 35 players (~50ms total), then 35 parallel DB writes (~500ms total).
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (!player.getTags().contains("player_synced") || player.isDeadOrDying()) continue;

                String puuid = player.getUUID().toString();
                try {
                    // Cache curios before snapshot
                    if (ModList.get().isLoaded("curios")) {
                        CuriosCache.tryStoreCuriosToCache(player);
                    }

                    // === MAIN THREAD: Snapshot (entity reads, fast) ===
                    final PlayerDataSnapshot snapshot = snapshotPlayerData(player);
                    final Map<UUID, CompoundTag> backpackSnapshots = ModsSupport.snapshotBackpackData(player);
                    // FIX C3: snapshot SS CompoundTags on main thread (was a background-thread read).
                    final Map<UUID, CompoundTag> ssSnapshots = ModsSupport.snapshotSSData(ModsSupport.collectSSUuids(player));
                    final List<UUID> rs2DiskUuids;
                    final ServerLevel rs2Level;
                    final HolderLookup.Provider rs2Registry;
                    if (ModList.get().isLoaded("refinedstorage")) {
                        rs2DiskUuids = ModsSupport.collectRS2DiskUuids(player);
                        rs2Level = player.serverLevel();
                        rs2Registry = player.getServer().registryAccess();
                    } else {
                        rs2DiskUuids = List.of();
                        rs2Level = null;
                        rs2Registry = null;
                    }

                    // === BACKGROUND THREAD: DB writes (parallel across all players) ===
                    futures.add(CompletableFuture.runAsync(() -> {
                        try {
                            // FIX ANTI-DUPLICATION: atomic data+online=0 with last_server guard
                            // FIX P0-2: short-circuit backpack/SS/RS2 if core write blocked.
                            boolean persisted = writeSnapshotToDB(snapshot, true);
                            if (persisted) {
                                ModsSupport.saveBackpackSnapshots(backpackSnapshots);
                                ModsSupport.saveSSSnapshots(ssSnapshots);
                                if (!rs2DiskUuids.isEmpty() && rs2Level != null) {
                                    ModsSupport.saveRS2DisksByLevel(rs2DiskUuids, rs2Level, rs2Registry);
                                }
                                PlayerSync.LOGGER.info("Saved player {} data on server shutdown", puuid);
                                SyncLogger.saveCompleted(puuid, "SHUTDOWN", 0);
                            } else {
                                PlayerSync.LOGGER.warn("Shutdown save: downstream backpack/SS/RS2 skipped for {} — core guard blocked", puuid);
                                SyncLogger.saveSkipped(puuid, "SHUTDOWN", "core guard blocked");
                            }
                        } catch (Exception e) {
                            PlayerSync.LOGGER.error("Error saving player {} on shutdown", puuid, e);
                            try {
                                JDBCsetUp.executePreparedUpdate("UPDATE " + Tables.playerData() + " SET online=0 WHERE uuid=? AND last_server=?",
                                        puuid, JdbcConfig.SERVER_ID.get());
                            } catch (Exception e2) {
                                PlayerSync.LOGGER.error("CRITICAL: Failed to mark player {} offline on shutdown", puuid, e2);
                            }
                        }
                    }, executorService));

                } catch (Exception e) {
                    PlayerSync.LOGGER.error("Error snapshotting player {} on shutdown", puuid, e);
                    try { JDBCsetUp.executePreparedUpdate("UPDATE " + Tables.playerData() + " SET online=0 WHERE uuid=? AND last_server=?", puuid, JdbcConfig.SERVER_ID.get()); }
                    catch (Exception ignored) {}
                }
            }

            // Wait for all parallel saves to complete (30s max to avoid watchdog kill)
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .get(30, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                PlayerSync.LOGGER.error("Timeout waiting for shutdown saves — {} tasks may not have completed", futures.size());
            } catch (Exception e) {
                PlayerSync.LOGGER.error("Error waiting for shutdown saves", e);
            }
        }
        JDBCsetUp.executePreparedUpdate("UPDATE " + Tables.serverInfo() + " SET enable=0 WHERE id=?", JdbcConfig.SERVER_ID.get());

        // Phase 3: stop heartbeat before pool shutdown so its tick doesn't race with pool close.
        vip.fubuki.playersync.util.HeartbeatService.stop();
        // Phase 4: stop periodic-save scheduler before pool shutdown.
        vip.fubuki.playersync.util.PeriodicSaveService.stop();

        // Shut down the background executor — no new tasks after this point
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException ignored) {
            executorService.shutdownNow();
        }

        // Close the HikariCP pool LAST — after all DB writes are guaranteed complete.
        // Previously this was in PlayerSync.onServerStopping which could fire BEFORE
        // this handler, closing the pool while shutdown saves were still running.
        JDBCsetUp.shutdownPool();
        // FIX REGRESSION: flush+shutdown the dedicated logger here, AFTER all shutdown
        // saves have logged their completion. Previously SyncLogger.shutdown() fired in
        // PlayerSync.onServerStopping, dropping every save log entry on the floor.
        vip.fubuki.playersync.util.SyncLogger.shutdown();
    }

    /**
     * Phase 3 emergency flush invoked from the JVM shutdown hook (kill -9, OOM, host
     * reboot) when {@code onServerShutdown} never ran. Runs on the JVM shutdown thread,
     * synchronously, WITHOUT the executor (which may be already draining or dead).
     *
     * <p>Best-effort: snapshots and writes every still-online player using direct
     * DB calls. No lock acquisition — the server is dying, we just want data on disk.
     * If the DB pool is already closed, we log and exit gracefully.
     */
    public static void emergencyFlushAll() {
        try {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) {
                PlayerSync.LOGGER.warn("[emergency-flush] no server instance — nothing to flush");
                return;
            }
            int flushed = 0;
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                String puuid = player.getUUID().toString();
                if (!player.getTags().contains("player_synced") || player.isDeadOrDying()) continue;
                try {
                    final PlayerDataSnapshot snapshot = snapshotPlayerData(player);
                    final Map<UUID, CompoundTag> backpackSnapshots = ModsSupport.snapshotBackpackData(player);
                    final Map<UUID, CompoundTag> ssSnapshots = ModsSupport.snapshotSSData(ModsSupport.collectSSUuids(player));
                    // Direct synchronous write (no executor, no lock).
                    boolean persisted = writeSnapshotToDB(snapshot, true);
                    if (persisted) {
                        ModsSupport.saveBackpackSnapshots(backpackSnapshots);
                        ModsSupport.saveSSSnapshots(ssSnapshots);
                        if (ModList.get().isLoaded("refinedstorage")) {
                            List<UUID> rs2 = ModsSupport.collectRS2DiskUuids(player);
                            if (!rs2.isEmpty()) {
                                ModsSupport.saveRS2DisksByLevel(rs2, player.serverLevel(), server.registryAccess());
                            }
                        }
                        SyncLogger.saveCompleted(puuid, "EMERGENCY_FLUSH", 0);
                        flushed++;
                    } else {
                        SyncLogger.saveSkipped(puuid, "EMERGENCY_FLUSH", "core guard blocked");
                    }
                } catch (Throwable t) {
                    PlayerSync.LOGGER.error("[emergency-flush] failed for {}: {}", puuid, t.getMessage());
                    SyncLogger.saveFailed(puuid, "EMERGENCY_FLUSH", t.getMessage());
                }
            }
            PlayerSync.LOGGER.warn("[emergency-flush] flushed {} players via shutdown hook", flushed);
        } catch (Throwable t) {
            PlayerSync.LOGGER.error("[emergency-flush] top-level failure", t);
        }
    }

    /**
     * FIX: Logout saves are now FULLY NON-BLOCKING on the main thread.
     *
     * OLD APPROACH (bad): snapshot on main thread, wait up to 15s for DB write → blocks
     * ALL server processing (ticks, other players' events) during that time.
     *
     * NEW APPROACH: snapshot on main thread (fast, pure memory), submit async DB write,
     * return immediately. The online flag stays 1 until the async save completes, which
     * naturally prevents premature rejoin via the kick mechanism + doPlayerJoin's new
     * pending-save wait logic.
     *
     * All branches now properly clean up syncNotCompletedPlayer + removePlayerLock
     * (previously leaked in the dead/sync-not-completed branches).
     */
    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        String player_uuid = event.getEntity().getUUID().toString();

        // Players kicked for duplicate login must NOT set online=0 — they're still
        // online on the OTHER server.
        if (kickedForDuplicateLogin.remove(player_uuid)) {
            PlayerSync.LOGGER.info("Player {} was kicked for duplicate login, NOT marking offline (still on other server)", player_uuid);
            SyncLogger.playerEvent(player_uuid, "KICKED_DUPLICATE", "Player on another server, not marking offline");
            syncNotCompletedPlayer.remove(player_uuid);
            removePlayerLock(player_uuid);
            return;
        }

        if (deadPlayerWhileLogging.remove(player_uuid)) {
            PlayerSync.LOGGER.warn("A dead or dying player was kicked, uuid: {}", player_uuid);
            // FIX PERF (C1): async — main thread does not wait for MySQL.
            executorService.execute(() -> {
                try {
                    JDBCsetUp.executePreparedUpdate("UPDATE " + Tables.playerData() + " SET online=0 WHERE uuid=?", player_uuid);
                } catch (SQLException e) {
                    PlayerSync.LOGGER.error("Error marking dead player offline: {}", player_uuid, e);
                }
            });
            syncNotCompletedPlayer.remove(player_uuid);
            removePlayerLock(player_uuid);
            return;
        }

        if (syncNotCompletedPlayer.remove(player_uuid)) {
            PlayerSync.LOGGER.warn("Player {} logged out with uncompleted sync. Data won't be saved for safety.", player_uuid);
            SyncLogger.saveSkipped(player_uuid, "LOGOUT", "Sync not completed — data preserved in DB, .dat data discarded");
            // FIX PERF (C1): async.
            executorService.execute(() -> {
                try {
                    JDBCsetUp.executePreparedUpdate("UPDATE " + Tables.playerData() + " SET online=0 WHERE uuid=?", player_uuid);
                } catch (SQLException e) {
                    PlayerSync.LOGGER.error("Error marking unsynced player offline: {}", player_uuid, e);
                }
            });
            removePlayerLock(player_uuid);
            return;
        }

        // === Normal save path ===
        Player player = event.getEntity();
        ReentrantLock lock = getPlayerLock(player_uuid);
        lock.lock();
        // Declared outside the try so the outer catch can complete/remove the future
        // if snapshot capture or task submission fails (see FIX REGRESSION below).
        CompletableFuture<Void> saveFuture = null;
        try {
            // FIX ANTI-DUPLICATION: Force-close the disconnecting player's container FIRST.
            // If another player is viewing this player's backpack, the container stays open
            // after disconnect. Items taken after the snapshot would be duplicated.
            // Closing the container menu ensures no further modifications can occur.
            if (player instanceof ServerPlayer sp && sp.containerMenu != sp.inventoryMenu) {
                sp.closeContainer();
            }
            // FIX CRITICAL ANTI-DUP: close every other player's container menu if it was
            // opened against this disconnecting player's inventory/backpack. If another
            // player keeps the container open and takes items after our snapshot, those
            // items are duplicated (the snapshot contains them, and the other player has them).
            // We conservatively close all non-inventory containers referencing this player's
            // inventory slots or any menu whose class name hints at a Sophisticated Backpacks
            // container. The viewer just sees their GUI close — no data loss.
            // FIX COMPAT: Close only containers that actually reference the disconnecting
            // player's inventory/enderchest. Previous version also closed any menu whose
            // class name contained "accessor"/"curio"/... which could force-close unrelated
            // mod menus mid-transaction. The slot-reference scan is both correct and safe
            // across every modded menu.
            if (player instanceof ServerPlayer disconnecting && disconnecting.getServer() != null) {
                net.minecraft.world.entity.player.Inventory srcInv = disconnecting.getInventory();
                net.minecraft.world.SimpleContainer srcEnder = disconnecting.getEnderChestInventory();
                for (ServerPlayer other : disconnecting.getServer().getPlayerList().getPlayers()) {
                    if (other == disconnecting) continue;
                    net.minecraft.world.inventory.AbstractContainerMenu menu = other.containerMenu;
                    if (menu == other.inventoryMenu) continue;
                    boolean shouldClose = false;
                    try {
                        for (net.minecraft.world.inventory.Slot slot : menu.slots) {
                            if (slot.container == srcInv || slot.container == srcEnder) {
                                shouldClose = true;
                                break;
                            }
                        }
                    } catch (Exception ignored) {}
                    if (shouldClose) {
                        try { other.closeContainer(); } catch (Exception ignored) {}
                    }
                }
            }

            // === MAIN THREAD: Snapshot ALL entity state (fast, no DB I/O) ===
            if (ModList.get().isLoaded("curios") && !player.isDeadOrDying()) {
                CuriosCache.tryStoreCuriosToCache((ServerPlayer) player);
            }

            final PlayerDataSnapshot snapshot = snapshotPlayerData(player);

            // Collect backpack/SS/RS2 data — snapshots on main thread (no async reads)
            final Map<UUID, CompoundTag> backpackSnapshots = ModsSupport.snapshotBackpackData(player);
            // FIX C3: SS CompoundTags snapshotted on main thread (frozen copies).
            final Map<UUID, CompoundTag> ssSnapshots = ModsSupport.snapshotSSData(ModsSupport.collectSSUuids(player));
            final List<UUID> rs2DiskUuids;
            final ServerLevel rs2Level;
            final HolderLookup.Provider rs2RegistryAccess;
            if (ModList.get().isLoaded("refinedstorage") && player instanceof ServerPlayer sp) {
                rs2DiskUuids = ModsSupport.collectRS2DiskUuids(player);
                rs2Level = sp.serverLevel();
                rs2RegistryAccess = sp.getServer().registryAccess();
            } else {
                rs2DiskUuids = List.of();
                rs2Level = null;
                rs2RegistryAccess = null;
            }

            // === NON-BLOCKING: submit async save, main thread returns immediately ===
            // The online flag stays 1 until the async save completes → kick mechanism
            // prevents premature rejoin on other servers, and pendingLogoutSaves prevents
            // premature rejoin on the same server.
            //
            // FIX CRITICAL RACE (B1): Register the future in pendingLogoutSaves BEFORE
            // submitting the work. Previously runAsync was submitted first — a fast
            // reconnect could observe pendingLogoutSaves.get(uuid)==null while the save
            // was already queued → doPlayerJoin would proceed without waiting.
            saveFuture = new CompletableFuture<>();
            pendingLogoutSaves.put(player_uuid, saveFuture);

            final CompletableFuture<Void> futureRef = saveFuture;
            // FIX REGRESSION: handle RejectedExecutionException if the executor is
            // already shut down (concurrent with server stop). Without this, the future
            // stays forever in pendingLogoutSaves and blocks future rejoins for 15s+.
            try {
                executorService.execute(() -> {
                // FIX CRITICAL ANTI-DUP (P0-d): acquire bgLock BEFORE any DB write so
                // concurrent SaveToFile / death-save BG tasks (using tryLock) either skip
                // cleanly OR wait until this logout finishes. Without this, a stale
                // auto-save queued before logout could overwrite fresh logout data.
                ReentrantLock bgLock = getPlayerLock(player_uuid);
                bgLock.lock();
                try {
                    // FIX ANTI-DUPLICATION: writeSnapshotToDB with setOffline=true
                    // atomically writes data + online=0 in a SINGLE UPDATE, AND guards
                    // with last_server to prevent stale overwrites. This eliminates the
                    // race where a slow async save overwrites fresher data from another server.
                    // FIX P0-2: short-circuit backpack/SS/RS2 saves if the core write was
                    // blocked by the last_server guard. Otherwise we overwrite the claiming
                    // server's backpack_data rows (which are keyed by storage UUID and do
                    // NOT carry a last_server guard themselves).
                    boolean persisted = writeSnapshotToDB(snapshot, true);
                    if (persisted) {
                        ModsSupport.saveBackpackSnapshots(backpackSnapshots);
                        ModsSupport.saveSSSnapshots(ssSnapshots);
                        if (!rs2DiskUuids.isEmpty() && rs2Level != null) {
                            ModsSupport.saveRS2DisksByLevel(rs2DiskUuids, rs2Level, rs2RegistryAccess);
                        }
                        PlayerSync.LOGGER.info("Logout save completed for player {}", player_uuid);
                        SyncLogger.saveCompleted(player_uuid, "LOGOUT", 0);
                    } else {
                        PlayerSync.LOGGER.warn("Logout save skipped downstream backpack/SS/RS2 for player {} — core guard blocked",
                                player_uuid);
                        SyncLogger.saveSkipped(player_uuid, "LOGOUT", "core guard blocked (another server claimed)");
                    }
                } catch (Exception e) {
                    PlayerSync.LOGGER.error("Error saving player {} data on logout", player_uuid, e);
                    SyncLogger.saveFailed(player_uuid, "LOGOUT", e.getMessage());
                    // If the atomic write failed, still try to set online=0
                    try {
                        JDBCsetUp.executePreparedUpdate("UPDATE " + Tables.playerData() + " SET online=0 WHERE uuid=? AND last_server=?",
                                player_uuid, JdbcConfig.SERVER_ID.get());
                    } catch (Exception e2) {
                        PlayerSync.LOGGER.error("CRITICAL: Failed to mark player {} offline", player_uuid, e2);
                    }
                } finally {
                    // FIX P0-d: remove playerLocks BEFORE unlocking bgLock so any
                    // auto-save BG that wakes right after unlock sees containsKey=false
                    // and skips cleanly.
                    removePlayerLock(player_uuid);
                    pendingLogoutSaves.remove(player_uuid);
                    futureRef.complete(null);
                    try { bgLock.unlock(); } catch (Exception ignored) {}
                }
                });
            } catch (java.util.concurrent.RejectedExecutionException rex) {
                // Executor is shut down (server stopping, or pool in unusable state) —
                // drain the future so no join thread is stuck waiting 15 s on .get().
                PlayerSync.LOGGER.warn("Logout save executor rejected task for player {} (likely shutdown in progress)", player_uuid);
                pendingLogoutSaves.remove(player_uuid);
                futureRef.completeExceptionally(rex);
                removePlayerLock(player_uuid);
            }

        } catch (Exception e) {
            PlayerSync.LOGGER.error("Error during player logout save for {}", player_uuid, e);
            try { JDBCsetUp.executePreparedUpdate("UPDATE " + Tables.playerData() + " SET online=0 WHERE uuid=? AND last_server=?", player_uuid, JdbcConfig.SERVER_ID.get()); }
            catch (Exception ignored) {}
            removePlayerLock(player_uuid);
            // FIX REGRESSION: if snapshot failed AFTER pendingLogoutSaves.put, complete
            // the future so a rejoining doPlayerJoin doesn't hang 15 s on .get().
            if (saveFuture != null) {
                pendingLogoutSaves.remove(player_uuid);
                saveFuture.completeExceptionally(e);
            }
        } finally {
            lock.unlock();
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
            // FIX: Include last_server in INSERT. Without this, last_server stays NULL,
            // and ALL subsequent writes with AND last_server=? fail silently → player data
            // is never saved → "players lose everything" on next login.
            JDBCsetUp.executePreparedUpdate(
                    "INSERT INTO " + Tables.playerData() + " (uuid, armor, inventory, enderchest, advancements, effects, xp, food_level, health, score, left_hand, cursors, online, last_server) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)",
                    player_uuid, equipment.toString(), inventoryMap.toString(), ender_chest.toString(), json, effectMap.toString(), XP, food_level, health, score, left_hand, cursors, JdbcConfig.SERVER_ID.get());
        } else {
            // FIX: Use COALESCE for advancements to avoid wiping valid DB data with empty string
            JDBCsetUp.executePreparedUpdate(
                    "UPDATE " + Tables.playerData() + " SET inventory=?, armor=?, xp=?, effects=?, enderchest=?, score=?, food_level=?, health=?, advancements=COALESCE(NULLIF(?, ''), advancements), left_hand=?, cursors=? WHERE uuid=?",
                    inventoryMap.toString(), equipment.toString(), XP, effectMap.toString(), ender_chest.toString(), score, food_level, health, json, left_hand, cursors, player_uuid);
        }
    }

    /**
     * Immutable snapshot of all player data, captured on the main thread.
     * Can be safely passed to a background thread for DB writes.
     */
    record PlayerDataSnapshot(
            String uuid, int xp, int score, int foodLevel, int health,
            String leftHand, String cursors,
            String equipment, String inventory, String enderChest, String effects,
            String advancements,
            // Mod data snapshots (serialized strings, thread-safe)
            String curiosData, String accessoriesData, String cosmeticArmorData, String attachmentsData
    ) {}

    /**
     * Captures all player data into an immutable snapshot on the MAIN THREAD.
     * This is fast (no DB I/O, just serialization to strings).
     */
    private static PlayerDataSnapshot snapshotPlayerData(Player player) throws Exception {
        String uuid = player.getUUID().toString();
        int XP = getTotalExperience(player);
        int score = player.getScore();
        int foodLevel = player.getFoodData().getFoodLevel();
        int health = (int) player.getHealth();
        String leftHand = getNbtForStorage(player.getItemInHand(net.minecraft.world.InteractionHand.OFF_HAND));
        String cursors = getNbtForStorage(player.containerMenu.getCarried());

        Map<Integer, String> equipmentMap = new HashMap<>();
        for (int i = 0; i < player.getInventory().armor.size(); i++) {
            equipmentMap.put(i, getNbtForStorage(player.getInventory().armor.get(i)));
        }
        Map<Integer, String> inventoryMap = new HashMap<>();
        for (int i = 0; i < player.getInventory().items.size(); i++) {
            inventoryMap.put(i, getNbtForStorage(player.getInventory().items.get(i)));
        }
        Map<Integer, String> enderChestMap = new HashMap<>();
        for (int i = 0; i < player.getEnderChestInventory().getContainerSize(); i++) {
            enderChestMap.put(i, getNbtForStorage(player.getEnderChestInventory().getItem(i)));
        }
        // FIX: Don't save effects for dead/dying players. Minecraft clears effects on
        // respawn, not on death — so a dead player's getActiveEffectsMap() still returns
        // pre-death effects. Previously, the death handler and logout-while-dead path both
        // saved these stale effects to DB, causing "phantom effects" on the next login
        // (player reconnects alive with effects they should have lost on death).
        Map<Integer, String> effectMap = new HashMap<>();
        if (!player.isDeadOrDying()) {
            for (Map.Entry<Holder<MobEffect>, MobEffectInstance> entry : player.getActiveEffectsMap().entrySet()) {
                MobEffectInstance effect = entry.getValue();
                // FIX: Skip infinite-duration effects. These come from:
                // - ReviveMe mod (downed state effects with Integer.MAX_VALUE duration)
                // - Beacons (ambient effects re-applied every tick while in range)
                // - Other mods that add permanent effects
                // Syncing these across servers causes phantom effects (player gets
                // downed-state effects or beacon effects on a server without the source).
                if (effect.isInfiniteDuration()) continue;
                Tag effectTag = effect.save();
                effectMap.put(BuiltInRegistries.MOB_EFFECT.getId(entry.getKey().value()), serialize(effectTag.toString()));
            }
        }

        // Advancements (file read, fast)
        // FIX: Default to null instead of "". When null, writeSnapshotToDB preserves
        // the existing DB value via COALESCE. Previously, if the file read failed
        // (save() threw, file missing, path wrong), "" was written to DB, silently
        // wiping all advancements every 5 minutes (periodic save) or on logout.
        String advancements = null;
        if (JdbcConfig.SYNC_ADVANCEMENTS.get() && player instanceof ServerPlayer sp) {
            try { sp.getAdvancements().save(); } catch (Exception ignored) {}
            Path path = sp.getServer().getServerDirectory().resolve(getSyncWorldForServer());
            File advFile = new File(path.toFile(), "/advancements/" + uuid + ".json");
            if (advFile.exists()) {
                String content = new String(Files.readAllBytes(advFile.toPath()), StandardCharsets.UTF_8);
                if (content != null && !content.isEmpty()) {
                    advancements = content;
                }
            }
        }

        // Mod data snapshots — entity reads, MUST be on main thread.
        // These are included in the snapshot so the background writer can persist them
        // without touching the entity again.
        String curiosData = ModList.get().isLoaded("curios") && !player.isDeadOrDying()
                ? ModsSupport.snapshotCuriosData(player) : null;
        String accessoriesData = ModCompatSync.snapshotAccessories(player);
        String cosmeticArmorData = ModCompatSync.snapshotCosmeticArmor(player);
        String attachmentsData = ModCompatSync.snapshotAttachments(player);

        // NOTE: Sophisticated Backpacks/Storage/RS2 saves are intentionally NOT in the
        // periodic snapshot — their contents live in server-side SavedData and are
        // always saved completely on logout / server shutdown.

        return new PlayerDataSnapshot(
                uuid, XP, score, foodLevel, health,
                leftHand, cursors,
                equipmentMap.toString(), inventoryMap.toString(), enderChestMap.toString(), effectMap.toString(),
                advancements,
                curiosData, accessoriesData, cosmeticArmorData, attachmentsData
        );
    }

    /**
     * Writes a snapshot to the DB. Runs on BACKGROUND THREAD — no entity access.
     * All data (basic + curios + mod compat) is written here in one pass.
     */
    /**
     * Writes a snapshot to the DB. Runs on BACKGROUND THREAD — no entity access.
     * All data (basic + curios + mod compat) is written here in one pass.
     *
     * FIX ANTI-DUPLICATION: All writes include AND last_server=? to prevent a stale
     * server (e.g. Server A crashing/shutting down slowly) from overwriting fresher
     * data saved by Server B after the player switched. If another server has already
     * claimed the player (changed last_server), these writes silently no-op.
     *
     * @param setOffline if true, atomically sets online=0 in the same UPDATE (used by
     *                   logout and shutdown saves). This eliminates the gap between data
     *                   write and flag set that previously allowed race conditions.
     */
    /**
     * Writes the core player snapshot to {@code player_data} (+ related tables)
     * under the {@code last_server} guard.
     *
     * @return {@code true} if the core UPDATE actually persisted rows, {@code false}
     *         if the guard blocked (another server claimed this player). Callers
     *         MUST short-circuit downstream writes (backpack / SS / RS2) when this
     *         returns {@code false} — otherwise they overwrite the claiming
     *         server's data. See P0-2 audit finding.
     */
    private static boolean writeSnapshotToDB(PlayerDataSnapshot s, boolean setOffline) throws Exception {
        int serverId = JdbcConfig.SERVER_ID.get();

        // FIX PERF: All writes batched into a SINGLE transaction on ONE connection.
        // Previously 4-8 separate connections × round-trips per player.
        // Now: 1 connection, 1 commit, automatic rollback on failure.
        String serverGuard = "(last_server=? OR last_server IS NULL)";
        String coreSql = setOffline
                ? "UPDATE " + Tables.playerData() + " SET inventory=?, armor=?, xp=?, effects=?, enderchest=?, score=?, food_level=?, health=?, advancements=COALESCE(?, advancements), left_hand=?, cursors=?, online=0, last_server=? WHERE uuid=? AND " + serverGuard
                : "UPDATE " + Tables.playerData() + " SET inventory=?, armor=?, xp=?, effects=?, enderchest=?, score=?, food_level=?, health=?, advancements=COALESCE(?, advancements), left_hand=?, cursors=?, last_server=? WHERE uuid=? AND " + serverGuard;

        // Build batch of all statements
        List<Object[]> batch = new ArrayList<>();

        // 1. Core player data
        batch.add(new Object[]{coreSql,
                s.inventory(), s.equipment(), s.xp(), s.effects(), s.enderChest(), s.score(), s.foodLevel(), s.health(), s.advancements(), s.leftHand(), s.cursors(), serverId, s.uuid(), serverId});

        // 2. Curios
        String curioGuard = "EXISTS (SELECT 1 FROM " + Tables.playerData() + " WHERE uuid=? AND " + serverGuard + ")";
        if (s.curiosData() != null) {
            batch.add(new Object[]{
                    "UPDATE " + Tables.curios() + " SET curios_item=? WHERE uuid=? AND " + curioGuard,
                    s.curiosData(), s.uuid(), s.uuid(), serverId});
            batch.add(new Object[]{
                    "INSERT IGNORE INTO " + Tables.curios() + " (uuid, curios_item) SELECT ?, ? FROM " + Tables.playerData() + " WHERE uuid=? AND " + serverGuard,
                    s.uuid(), s.curiosData(), s.uuid(), serverId});
        }

        // 3. Mod compat data (Accessories, CosmeticArmor, NeoForge attachments)
        addModDataToBatch(batch, s.uuid(), "accessories", s.accessoriesData(), serverId, serverGuard);
        addModDataToBatch(batch, s.uuid(), "cosmeticarmor", s.cosmeticArmorData(), serverId, serverGuard);
        addModDataToBatch(batch, s.uuid(), "neoforge_attachments", s.attachmentsData(), serverId, serverGuard);

        // Execute all in one transaction. First statement is the core UPDATE on
        // player_data — if it affects 0 rows, the last_server guard blocked the write
        // (another server already claimed this player). Logging this is crucial for
        // diagnosing silent data-loss scenarios that were previously invisible.
        int[] counts = JDBCsetUp.executeBatchTransaction(batch.toArray(new Object[0][]));
        if (counts.length > 0 && counts[0] == 0) {
            SyncLogger.guardBlocked(s.uuid(), serverId,
                    "core UPDATE affected 0 rows — player_data.last_server no longer matches this server or row was removed");
            PlayerSync.LOGGER.warn(
                    "PlayerSync: core write blocked by last_server guard for {} (server={}). Data was NOT persisted — another server has claimed this player.",
                    s.uuid(), serverId);
            return false;
        }
        return true;
    }

    private static void addModDataToBatch(List<Object[]> batch, String uuid, String modId, String data, int serverId, String serverGuard) {
        if (data == null) return;
        batch.add(new Object[]{
                "UPDATE " + Tables.modPlayerData() + " SET data_value=? WHERE uuid=? AND mod_id=? AND EXISTS (SELECT 1 FROM " + Tables.playerData() + " WHERE uuid=? AND " + serverGuard + ")",
                data, uuid, modId, uuid, serverId});
        batch.add(new Object[]{
                "INSERT IGNORE INTO " + Tables.modPlayerData() + " (uuid, mod_id, data_value) SELECT ?, ?, ? FROM " + Tables.playerData() + " WHERE uuid=? AND " + serverGuard,
                uuid, modId, data, uuid, serverId});
    }

    /** Backwards-compatible overload for periodic saves (no offline flag). */
    private static boolean writeSnapshotToDB(PlayerDataSnapshot s) throws Exception {
        return writeSnapshotToDB(s, false);
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
    private static final int AUTO_SAVE_INTERVAL_TICKS = 6000; // Every 5 minutes (20 tps × 300s)
    // FIX PERF: Staggered auto-save. Instead of snapshotting ALL 35 players in one tick
    // (770-3605ms spike → 15-36s TPS drop), we save 1 player per tick over 35 ticks
    // (22-103ms per tick → imperceptible). The queue is refilled every AUTO_SAVE_INTERVAL.
    private static final List<ServerPlayer> autoSaveQueue = new ArrayList<>();
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
                    JDBCsetUp.executePreparedUpdate("UPDATE " + Tables.serverInfo() + " SET last_update=? WHERE id=?",
                            System.currentTimeMillis(), JdbcConfig.SERVER_ID.get());
                } catch (SQLException e) {
                    PlayerSync.LOGGER.error("Error updating server heartbeat", e);
                }
            });
        }

        // Auto-save: snapshot ALL entity data on MAIN THREAD (fast, no I/O), then write
        // to DB on a BACKGROUND THREAD.
        //
        // FIX: Previously the background task called ModCompatSync.storeAll(player),
        // storeSophisticatedBackpacks(player), etc. from off-thread — accessing entity
        // state (inventory, Accessories API, CosmeticArmor, NeoForge attachments) in a
        // non-thread-safe way.  All entity reads are now done in snapshotPlayerData()
        // on the main thread, and the background task only does DB writes.
        //
        // FIX PERF: Staggered auto-save — saves ONE player per tick instead of ALL at once.
        // Old behavior: 35 players snapshotted in ONE tick → 770-3605ms MSPT spike every 5 min.
        // New behavior: queue refilled every 5 min, then drained 1 player/tick → 22-103ms/tick max.
        // Backpack contents are included (prevents data loss on hard crash).
        if (autoSaveTickCounter >= AUTO_SAVE_INTERVAL_TICKS) {
            autoSaveTickCounter = 0;
            // Refill the queue with all eligible players
            autoSaveQueue.clear();
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                autoSaveQueue.addAll(server.getPlayerList().getPlayers());
            }
        }

        // Process ONE player from the queue per tick (staggered)
        if (!autoSaveQueue.isEmpty()) {
            ServerPlayer player = autoSaveQueue.removeFirst();
            String puuid = player.getUUID().toString();

            // Skip invalid players (same guards as before)
            if (!player.isDeadOrDying() && !syncNotCompletedPlayer.contains(puuid)
                    && !pendingLogoutSaves.containsKey(puuid) && player.getTags().contains("player_synced")) {
                ReentrantLock lock = getPlayerLock(puuid);
                if (lock.tryLock()) {
                    try {
                        final PlayerDataSnapshot snapshot = snapshotPlayerData(player);
                        final Map<UUID, CompoundTag> backpackSnapshots = ModsSupport.snapshotBackpackData(player);

                        executorService.submit(() -> {
                            // FIX P0-a/b/c (staggered auto-save BG): same triple guard as SaveToFile.
                            if (pendingLogoutSaves.containsKey(puuid)) return;
                            ReentrantLock bgLock = getPlayerLock(puuid);
                            if (!bgLock.tryLock()) return;
                            try {
                                if (pendingLogoutSaves.containsKey(puuid)) return;
                                try (JDBCsetUp.QueryResult oc = JDBCsetUp.executePreparedQuery(
                                        "SELECT online FROM " + Tables.playerData() + " WHERE uuid=?", puuid)) {
                                    ResultSet rs = oc.resultSet();
                                    if (rs.next() && rs.getInt("online") == 0) {
                                        SyncLogger.guardBlocked(puuid, JdbcConfig.SERVER_ID.get(),
                                                "Staggered auto-save BG skipped — player offline in DB");
                                        return;
                                    }
                                }
                                boolean persisted = writeSnapshotToDB(snapshot);
                                if (persisted) {
                                    ModsSupport.saveBackpackSnapshots(backpackSnapshots);
                                } else {
                                    PlayerSync.LOGGER.warn("Staggered auto-save: core write blocked for {}", puuid);
                                    SyncLogger.saveSkipped(puuid, "AUTO", "core guard blocked");
                                }
                            } catch (Exception e) {
                                PlayerSync.LOGGER.error("Error auto-saving player {}", puuid, e);
                            } finally {
                                bgLock.unlock();
                            }
                        });
                    } catch (Exception e) {
                        PlayerSync.LOGGER.error("Error snapshotting player {}", puuid, e);
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

    // FIX COMPAT (C1): priority=LOW + skip canceled events defends against mods like
    // Revive Me / Corail Tombstone / Hardcore Revival that cancel LivingDeathEvent at
    // NORMAL/HIGH priority. At LOW we run after them, and the cancel check short-circuits
    // the death-save so "fallen" players are not mistakenly treated as dead.
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOW)
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (event.isCanceled()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        String puuid = player.getUUID().toString();
        if (deadPlayerWhileLogging.contains(puuid)) return;

        // Always cache curios on death (API returns empty for dead players later)
        CuriosCache.tryStoreCuriosToCache(player);

        // Immediately save ALL player data on death (snapshot + async).
        // LivingDeathEvent fires BEFORE items are dropped, so the snapshot captures
        // the full pre-death inventory including backpack contents.
        // This protects against: server crash after death, network disconnect before
        // onPlayerLogout fires, or any scenario where the logout handler is skipped.
        // The normal logout save will overwrite this with the final post-death state.
        if (!player.getTags().contains("player_synced")) return;
        if (syncNotCompletedPlayer.contains(puuid)) return;
        if (pendingLogoutSaves.containsKey(puuid)) return; // logout save already in flight

        ReentrantLock lock = getPlayerLock(puuid);
        if (!lock.tryLock()) return; // Skip if another save is in progress
        try {
            final PlayerDataSnapshot snapshot = snapshotPlayerData(player);
            final Map<UUID, CompoundTag> backpackSnapshots = ModsSupport.snapshotBackpackData(player);
            final Map<UUID, CompoundTag> ssSnapshots = ModsSupport.snapshotSSData(ModsSupport.collectSSUuids(player));
            final List<UUID> rs2DiskUuids;
            final ServerLevel rs2Level;
            final HolderLookup.Provider rs2Registry;
            if (ModList.get().isLoaded("refinedstorage")) {
                rs2DiskUuids = ModsSupport.collectRS2DiskUuids(player);
                rs2Level = player.serverLevel();
                rs2Registry = player.getServer().registryAccess();
            } else {
                rs2DiskUuids = List.of();
                rs2Level = null;
                rs2Registry = null;
            }

            executorService.submit(() -> {
                if (!playerLocks.containsKey(puuid)) return;
                // FIX CRITICAL ANTI-DUP (P0-a): early skip if logout is already in flight.
                if (pendingLogoutSaves.containsKey(puuid)) return;
                ReentrantLock bgLock = getPlayerLock(puuid);
                if (!bgLock.tryLock()) return;
                try {
                    // FIX CRITICAL ANTI-DUP (P0-b): re-check under lock.
                    if (pendingLogoutSaves.containsKey(puuid)) return;
                    // FIX CRITICAL ANTI-DUP (P0-c): skip if logout has already committed.
                    try (JDBCsetUp.QueryResult onlineCheck = JDBCsetUp.executePreparedQuery(
                            "SELECT online FROM " + Tables.playerData() + " WHERE uuid=?", puuid)) {
                        ResultSet rs = onlineCheck.resultSet();
                        if (rs.next() && rs.getInt("online") == 0) {
                            SyncLogger.guardBlocked(puuid, JdbcConfig.SERVER_ID.get(),
                                    "Death-save BG skipped — player already offline in DB");
                            return;
                        }
                    }
                    // FIX P0-2: short-circuit backpack/SS/RS2 if core guard blocked.
                    boolean persisted = writeSnapshotToDB(snapshot);
                    if (persisted) {
                        ModsSupport.saveBackpackSnapshots(backpackSnapshots);
                        ModsSupport.saveSSSnapshots(ssSnapshots);
                        if (!rs2DiskUuids.isEmpty() && rs2Level != null) {
                            ModsSupport.saveRS2DisksByLevel(rs2DiskUuids, rs2Level, rs2Registry);
                        }
                        PlayerSync.LOGGER.info("Death-save completed for player {}", puuid);
                        SyncLogger.saveCompleted(puuid, "DEATH", 0);
                    } else {
                        PlayerSync.LOGGER.warn("Death-save: core write blocked for {} — downstream skipped", puuid);
                        SyncLogger.saveSkipped(puuid, "DEATH", "core guard blocked");
                    }
                } catch (Exception e) {
                    PlayerSync.LOGGER.error("Error death-saving player {}", puuid, e);
                } finally {
                    bgLock.unlock();
                }
            });
        } catch (Exception e) {
            PlayerSync.LOGGER.error("Error snapshotting player {} on death", puuid, e);
        } finally {
            lock.unlock();
        }
    }
}