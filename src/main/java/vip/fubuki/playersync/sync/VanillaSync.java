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
    //
    // AUDIT FIX (pool sizing): standard ThreadPoolExecutor semantics only create threads
    // beyond corePoolSize when the work queue is FULL. The previous 4..16 config was
    // therefore permanently pinned at 4 threads (the queue would need 512 backlogged
    // tasks before thread #5 appeared) — every save path funneled through 4 workers
    // and the "35 parallel shutdown saves" assumption was wrong (only 4 ran at once).
    // core == max == 16 + allowCoreThreadTimeOut(true) gives real 16-wide parallelism
    // while still reaping idle threads after 30s.
    //
    // AUDIT FIX (rejection policy): CallerRunsPolicy ran the rejected task inline on
    // the SUBMITTING thread. For tasks submitted from the server main thread
    // (onServerTick, snapshotAndQueueSave, onPlayerJoin) that meant full DB
    // transactions — or doPlayerJoin's up-to-60s poll loop — executing ON the main
    // thread under queue overflow: a multi-second to multi-minute freeze.
    // MainThreadSafeBackpressurePolicy keeps CallerRuns backpressure for background
    // submitters but hands main-thread overflow to a dedicated single-thread overflow
    // executor so the tick thread NEVER blocks and no save is ever dropped.
    static ExecutorService executorService = new ThreadPoolExecutor(
            16,                         // core pool size (== max: see AUDIT FIX above)
            16,                         // maximum pool size
            30L, TimeUnit.SECONDS,      // idle thread keepalive (applies to core threads too)
            new LinkedBlockingQueue<>(512),  // bounded work queue
            new PSThreadPoolFactory("PlayerSync"),
            new MainThreadSafeBackpressurePolicy()
    );

    static {
        ((ThreadPoolExecutor) executorService).allowCoreThreadTimeOut(true);
    }

    /**
     * Overflow lane for tasks rejected while being submitted FROM the server main
     * thread. Unbounded queue, single thread: absorbs rare overflow bursts without
     * ever blocking a tick. Never used by background submitters (they get classic
     * CallerRuns backpressure instead).
     */
    private static final ExecutorService overflowExecutor =
            Executors.newSingleThreadExecutor(new PSThreadPoolFactory("PlayerSync-overflow"));

    /**
     * AUDIT FIX: rejection policy that never executes blocking work on the server
     * main thread.
     * <ul>
     *   <li>Executor shut down → silently drop (same semantics as CallerRunsPolicy
     *       during shutdown; the shutdown-save path has already flushed players).</li>
     *   <li>Submitted from the server main thread → run on {@link #overflowExecutor}
     *       (never inline — a queued save or a join poll must not stall ticks).</li>
     *   <li>Submitted from any other thread → run inline (classic CallerRuns
     *       backpressure; dropping saves would mean data loss).</li>
     * </ul>
     */
    private static final class MainThreadSafeBackpressurePolicy implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            if (executor.isShutdown()) return;
            MinecraftServer srv = ServerLifecycleHooks.getCurrentServer();
            if (srv != null && Thread.currentThread() == srv.getRunningThread()) {
                PlayerSync.LOGGER.warn("[executor] queue full ({} tasks) — diverting main-thread submission to overflow lane",
                        executor.getQueue().size());
                overflowExecutor.execute(r);
            } else {
                r.run();
            }
        }
    }

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

    /** Admin-command accessor for the shared executor — read-only usage. */
    public static ThreadPoolExecutor getExecutor() {
        return (ThreadPoolExecutor) executorService;
    }

    public static void removePlayerLock(String uuid) {
        playerLocks.remove(uuid);
        lastWrittenSnapshotHash.remove(uuid);
        // AUDIT FIX (memory): evict the player's advancements cache entry. The cache
        // key is the absolute file path which always contains the player UUID
        // ("/advancements/<uuid>.json"), so contains() matches exactly one entry.
        // Without this the map retained the FULL advancements JSON (100KB-1MB+ on
        // large modpacks) for every player that ever joined — unbounded growth on
        // long-running hub servers. Cost of eviction: one disk re-read on the
        // player's next-session first snapshot (mtime check guarantees freshness).
        advancementsFileCache.keySet().removeIf(k -> k.contains(uuid));
        lastAppliedAdvancementsHash.remove(uuid);
    }

    /**
     * FIX DUP-REVIVE: returns true if the player is currently in the Revive Me mod's
     * "fallen" (downed) state — the "dead but still alive" phase shown after lethal
     * damage when Revive Me intercepts the death.
     *
     * <p>Queried reflectively against Revive Me's own state
     * ({@code invoker54.reviveme.common.capability.FallenData.get(player).isFallen()})
     * so PlayerSync keeps a soft (optional) dependency on the mod. The FallenData is a
     * NeoForge AttachmentType ({@code revive_me:fallen_data}); it is serialized into
     * the player's .dat file, so on a SAME-server reconnect the entity is already
     * carrying the fallen flag by the time {@link #doPlayerJoin} runs.
     *
     * <p>This is an EXACT signal — no heuristics, no false positives. A player is
     * fallen if and only if Revive Me says so.
     *
     * @return true only if the {@code revive_me} mod is loaded AND the player is
     *         currently fallen; false on any error or if the mod is absent.
     */
    // AUDIT FIX (perf): lazily-cached reflection handles for ReviveMe's FallenData.
    // Previously every call resolved Class.forName + 2× getMethod (each getMethod
    // returns a fresh Method copy — allocation + security check) and the function
    // runs on every logout, every join apply, and was re-invoked inside log
    // statements. Resolve once, publish via volatile (benign race), reuse forever.
    private static volatile java.lang.reflect.Method REVIVEME_FALLEN_GET;
    private static volatile java.lang.reflect.Method REVIVEME_IS_FALLEN;
    private static volatile boolean reviveMeReflectionFailed = false;

    public static boolean isReviveMeFallen(net.minecraft.world.entity.player.Player player) {
        if (player == null) return false;
        if (reviveMeReflectionFailed) return false;
        if (!ModList.get().isLoaded("revive_me")) return false;
        try {
            java.lang.reflect.Method getMethod = REVIVEME_FALLEN_GET;
            java.lang.reflect.Method isFallenMethod = REVIVEME_IS_FALLEN;
            if (getMethod == null || isFallenMethod == null) {
                Class<?> fallenDataClass = Class.forName("invoker54.reviveme.common.capability.FallenData");
                getMethod = fallenDataClass.getMethod("get", net.minecraft.world.entity.LivingEntity.class);
                isFallenMethod = fallenDataClass.getMethod("isFallen");
                REVIVEME_FALLEN_GET = getMethod;
                REVIVEME_IS_FALLEN = isFallenMethod;
            }
            Object fallenData = getMethod.invoke(null, player);
            if (fallenData == null) return false;
            Object result = isFallenMethod.invoke(fallenData);
            return result instanceof Boolean b && b;
        } catch (Throwable t) {
            // Resolution failure is permanent (class/method shape mismatch) — stop
            // retrying the reflection on every event. Invocation failures on a
            // resolved handle are unexpected; treat identically (fail-safe false).
            if (REVIVEME_FALLEN_GET == null || REVIVEME_IS_FALLEN == null) {
                reviveMeReflectionFailed = true;
                PlayerSync.LOGGER.warn("[revive-detect] ReviveMe FallenData reflection unavailable — fallen detection disabled: {}", t.toString());
            } else {
                PlayerSync.LOGGER.debug("[revive-detect] could not query ReviveMe FallenData for {}: {}",
                        player.getUUID(), t.toString());
            }
            return false;
        }
    }

    /**
     * PHASE 17 PERF: advancements JSON cache keyed by absolute file path.
     * Keeps the mtime along with the content — a mismatch on either forces a
     * fresh disk read. The cache is process-wide (not per-player) because the
     * path already includes the player UUID.
     */
    private static final ConcurrentHashMap<String, AdvancementsCacheEntry> advancementsFileCache = new ConcurrentHashMap<>();

    private static final class AdvancementsCacheEntry {
        final long mtime;
        final String content;
        AdvancementsCacheEntry(long mtime, String content) {
            this.mtime = mtime;
            this.content = content;
        }
    }

    /**
     * PHASE 7 PERF: per-player hash of the last successfully-written snapshot.
     * Auto-save / periodic / dimension-change BG tasks skip the DB write when
     * the new snapshot hashes identical to the last-written one — on an idle
     * server with 35 players this cuts 95%+ of redundant UPDATE traffic.
     *
     * <p>Never used by logout/shutdown/death paths: those MUST always write
     * to guarantee online=0 atomicity and capture the final state.
     */
    private static final ConcurrentHashMap<String, Integer> lastWrittenSnapshotHash = new ConcurrentHashMap<>();

    /** Cheap hash over the serialized snapshot. */
    private static int computeSnapshotHash(PlayerDataSnapshot s) {
        int h = 17;
        h = 31 * h + java.util.Objects.hashCode(s.inventory());
        h = 31 * h + java.util.Objects.hashCode(s.equipment());
        h = 31 * h + java.util.Objects.hashCode(s.enderChest());
        h = 31 * h + java.util.Objects.hashCode(s.effects());
        h = 31 * h + java.util.Objects.hashCode(s.leftHand());
        h = 31 * h + java.util.Objects.hashCode(s.cursors());
        h = 31 * h + java.util.Objects.hashCode(s.advancements());
        h = 31 * h + java.util.Objects.hashCode(s.curiosData());
        h = 31 * h + java.util.Objects.hashCode(s.accessoriesData());
        h = 31 * h + java.util.Objects.hashCode(s.cosmeticArmorData());
        h = 31 * h + java.util.Objects.hashCode(s.attachmentsData());
        h = 31 * h + s.xp();
        h = 31 * h + s.foodLevel();
        h = 31 * h + s.health();
        h = 31 * h + s.score();
        return h;
    }

    /**
     * Checks if a player is still in the server's online player list.
     * Used to avoid applying sync data to a player entity that already disconnected.
     * <p>PERF (A5): O(1) lookup via PlayerList's internal UUID→player map instead of
     * the previous O(n) iteration with a per-player {@code UUID#toString} allocation.
     */
    private static boolean isPlayerOnline(MinecraftServer server, String uuid) {
        try {
            return server.getPlayerList().getPlayer(UUID.fromString(uuid)) != null;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    @SubscribeEvent
    public static void onDataPackSyncEvent(OnDatapackSyncEvent event) {
        if (!JdbcConfig.SYNC_ADVANCEMENTS.get())
            return; // advancement sync disabled

        final ServerPlayer serverPlayer = event.getPlayer();
        if (serverPlayer == null) {
            PlayerSync.LOGGER.debug("No player joining");
            return;
        }

        final String player_uuid = serverPlayer.getUUID().toString();
        PlayerSync.LOGGER.info("Player entity joining level {}", player_uuid);

        // AUDIT FIX (main-thread I/O): the advancements SELECT used to run
        // synchronously inside this MAIN-THREAD event handler — one blocking MySQL
        // round-trip per join (worst case 10s connectionTimeout). The SELECT now
        // runs on the executor; ONLY the file write + reload() hop back to the main
        // thread (they must stay there: snapshotPlayerData reads the same file on
        // the main thread, and reload() mutates PlayerAdvancements).
        executorService.submit(() -> {
            try {
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

                final byte[] bytes = advancementsData.getBytes(StandardCharsets.UTF_8);

                // PERF (A3): skip the file write + playeradvancements.reload() if the DB content
                // is identical to what we last applied for this player. reload() walks every
                // criterion of every advancement and can take 5-50 ms on a large datapack.
                // CRC32 is enough — collisions on advancement JSON are astronomically unlikely
                // and a stale skip just means the player sees their progression with the same
                // (already-applied) data, never with corruption.
                java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                crc.update(bytes);
                final long contentHash = crc.getValue();
                Long lastHash = lastAppliedAdvancementsHash.get(player_uuid);
                if (lastHash != null && lastHash == contentHash) {
                    PlayerSync.LOGGER.debug("Skip advancements re-apply for {} (CRC32 unchanged: {})", player_uuid, contentHash);
                    return;
                }

                final MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                if (server == null) return;

                // MAIN THREAD: file write + reload (entity + main-thread-read file access).
                server.execute(() -> {
                    try {
                        if (!isPlayerOnline(server, player_uuid)) {
                            PlayerSync.LOGGER.debug("Player {} left before advancements apply", player_uuid);
                            return;
                        }
                        Path path = server.getServerDirectory().resolve(getSyncWorldForServer());
                        File gameDir = path.toFile();

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
                        // PERF (A3): record the hash of what we just applied. Next call with the
                        // same DB content short-circuits before touching the disk and reload().
                        lastAppliedAdvancementsHash.put(player_uuid, contentHash);
                    } catch (Exception e) {
                        PlayerSync.LOGGER.error("Error applying advancements for player {}", player_uuid, e);
                    }
                });
            } catch (Exception e) {
                PlayerSync.LOGGER.error("Error reading advancements for player {}", player_uuid, e);
            }
        });
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
                    connectCheckCache.put(player_uuid, new CachedConnectCheck(new int[]{0, 0, 0, 0}, System.currentTimeMillis())); // new player
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
            connectCheckCache.put(player_uuid, new CachedConnectCheck(
                    new int[]{online ? 1 : 0, lastServer, serverAlive, alreadyKicked},
                    System.currentTimeMillis()));
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
    // int[0]=online(0/1), int[1]=lastServer, int[2]=serverAlive(0/1), int[3]=alreadyKicked(0/1)
    //
    // PERF (A7): wrapped in a record with insertion timestamp so we can purge stale
    // entries when PlayerNegotiationEvent fires but PlayerLoggedInEvent never does
    // (player drops the connection mid-handshake). The sweep runs on the existing
    // server-tick handler — no extra thread.
    private record CachedConnectCheck(int[] data, long insertedAt) {}
    private static final ConcurrentHashMap<String, CachedConnectCheck> connectCheckCache = new ConcurrentHashMap<>();
    private static final long CONNECT_CHECK_TTL_MS = 60_000L; // 60 s — generous: handshake usually completes in < 5 s
    private static long lastConnectCheckSweepTick = 0L;

    // PERF (A3): per-UUID hash of the last advancements payload we wrote to disk.
    // onDataPackSyncEvent currently always Files.writes + playeradvancements.reload()s
    // on the main thread; reload() walks every criterion of every advancement which
    // can be 5-50 ms on a large datapack. Skip both when the DB content hashes to the
    // same value we last applied for this player.
    private static final ConcurrentHashMap<String, Long> lastAppliedAdvancementsHash = new ConcurrentHashMap<>();

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
        final long restoreT0 = System.currentTimeMillis();
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
            // ================================================================
            // PHASE 15: 2-phase-commit-aware join protocol
            // ================================================================
            // The player_data row now carries three cross-server signals:
            //   online            (0 = not on any server, 1 = on some server)
            //   last_server       (which server claimed ownership)
            //   logout_started_at (NOT NULL = save in progress on that server,
            //                      NULL = no in-flight save)
            //
            // Decision matrix (online=1 branch):
            //   last_server=self  -> we already own (shouldn't happen on fresh
            //                        join, but harmless — proceed)
            //   last_server=peer  + logout_started_at IS NULL
            //                     -> peer has ACTIVE session. Kick if the
            //                        kick_when_already_online policy is on;
            //                        otherwise force-claim (accepts the risk).
            //   last_server=peer  + logout_started_at = recent (< 10s)
            //                     -> peer is mid-save. Wait briefly.
            //   last_server=peer  + logout_started_at = stale (> 10s)
            //                     -> ghost session (peer crashed mid-save,
            //                        SIGKILL, process frozen). Force-claim.
            //   peer heartbeat stale (> peer_stale_threshold_seconds)
            //                     -> peer is dead regardless of logout flag.
            //                        Force-claim instantly.
            // online=0            -> clean state, claim immediately.
            //
            // The claim UPDATE is a CAS:
            //   WHERE uuid=? AND (online=0 OR last_server=? OR <force-claim>)
            // so two concurrent joining servers can never both succeed.
            // ================================================================
            final int MAX_POLL = JdbcConfig.JOIN_POLL_MAX_ATTEMPTS.get();
            final int POLL_INTERVAL_MS = JdbcConfig.JOIN_POLL_INTERVAL_MS.get();
            final long STALE_HEARTBEAT_MS = JdbcConfig.PEER_STALE_THRESHOLD_SECONDS.get() * 1000L;
            // logout_started_at age beyond which we treat a 'save in progress'
            // as actually stuck (peer crashed mid-save). Saves typically complete
            // in < 1s, so 10s is 10× safety margin.
            final long LOGOUT_SAVE_MAX_MS = 10_000L;
            final int SELF = JdbcConfig.SERVER_ID.get();

            boolean forceClaim = false;   // bypass online=0 / last_server=self guard
            // PHASE 18.1 FIX: track whether the row exists at all. A brand-new player
            // has no row yet — the CAS claim below must be skipped (it would return
            // 0 rows affected, which the old code misinterpreted as 'another server
            // claimed first' and wrongly kicked the player with the 'finalizing your
            // save' message on their very first connection). For new players the row
            // gets INSERTed later by the async init write in the new-player branch.
            boolean isNewPlayer = false;
            final long pollStartTime = System.currentTimeMillis();
            for (int attempt = 0; attempt < MAX_POLL; attempt++) {
                int otherServer;
                boolean otherOnline;
                long logoutStartedAt;     // 0 = NULL (no save in progress)
                boolean rowExists;

                try (JDBCsetUp.QueryResult qrCheck = JDBCsetUp.executePreparedQuery(
                        "SELECT online, last_server, COALESCE(logout_started_at, 0) AS lsa FROM "
                                + Tables.playerData() + " WHERE uuid=?", player_uuid)) {
                    ResultSet rsCheck = qrCheck.resultSet();
                    rowExists = rsCheck.next();
                    if (!rowExists) {
                        isNewPlayer = true;
                        break; // new player — nothing to wait for, skip CAS
                    }
                    otherServer = rsCheck.getInt("last_server");
                    otherOnline = rsCheck.getBoolean("online");
                    logoutStartedAt = rsCheck.getLong("lsa");
                }

                // Fast path: row is clean or already ours.
                if (!otherOnline || otherServer == SELF) break;

                // Peer heartbeat fully stale => peer process dead, force-claim.
                if (otherServer == 0 || isPeerServerStale(otherServer, STALE_HEARTBEAT_MS)) {
                    SyncLogger.raceCondition(player_uuid,
                            "Peer " + otherServer + " heartbeat stale — force-claiming after " + attempt + " attempts");
                    forceClaim = true;
                    break;
                }

                long now = System.currentTimeMillis();
                long waitedMs = now - pollStartTime;

                if (logoutStartedAt > 0) {
                    long saveAgeMs = now - logoutStartedAt;
                    if (saveAgeMs > LOGOUT_SAVE_MAX_MS) {
                        // Peer marked logout-in-progress but never cleared it ->
                        // save thread died mid-flight. Force-claim.
                        SyncLogger.raceCondition(player_uuid,
                                "Peer " + otherServer + " logout save stalled " + saveAgeMs
                                        + "ms (> " + LOGOUT_SAVE_MAX_MS + "ms) — force-claiming");
                        forceClaim = true;
                        break;
                    }
                    // Peer is actively committing; it writes logout_started_at=NULL
                    // + online=0 atomically on success. Give it a short poll cycle.
                    if ((attempt % 10) == 0) {
                        SyncLogger.raceCondition(player_uuid,
                                "Peer " + otherServer + " save in flight (logout_age=" + saveAgeMs
                                        + "ms, attempt=" + (attempt + 1) + "/" + MAX_POLL + ")");
                    }
                    Thread.sleep(POLL_INTERVAL_MS);
                    continue;
                }

                // online=1 AND logout_started_at IS NULL: peer has an ACTIVE session.
                // The joining player is racing an actual player on another server.
                // onPlayerLoggedInKickCheck already ran and either kicked us or cached
                // a 'not kicked' decision — so at this point we can treat it as a
                // ghost session (the other session didn't get its kick because the
                // cache was empty / peer's heartbeat just landed), and force-claim.
                // If kick_when_already_online is true, the player who SHOULD be kicked
                // is the one who lost the race — not us.
                if (waitedMs >= 2000L) {
                    SyncLogger.raceCondition(player_uuid,
                            "Peer " + otherServer + " online=1 without logout flag — ghost session, force-claiming (waited " + waitedMs + "ms)");
                    forceClaim = true;
                    break;
                }
                if ((attempt % 10) == 0) {
                    SyncLogger.raceCondition(player_uuid,
                            "Peer " + otherServer + " online=1 but no logout_started_at — brief grace period (waited=" + waitedMs + "ms)");
                }
                Thread.sleep(POLL_INTERVAL_MS);
            }

            // ================================================================
            // CLAIM with atomic CAS. Two concurrent joining servers can never
            // both succeed — the one that lands its UPDATE second sees 0 rows
            // affected and aborts its restore.
            //
            // PHASE 18.1: new players skip the CAS entirely. No row exists yet,
            // so UPDATE affects 0 rows by definition — the old code was kicking
            // FIRST-TIME joiners with "another server is finalizing your save".
            // The new-player branch will INSERT the row with the correct state
            // in a moment (async init write).
            // ================================================================
            if (!isNewPlayer) {
                int claimed;
                if (forceClaim) {
                    // Unconditional — we've decided the previous owner is defunct.
                    claimed = JDBCsetUp.executePreparedUpdateRet(
                            "UPDATE " + Tables.playerData()
                                    + " SET last_server=?, online=1, logout_started_at=NULL WHERE uuid=?",
                            SELF, player_uuid);
                } else {
                    // Guarded — only claim if the row is actually clean or already ours.
                    claimed = JDBCsetUp.executePreparedUpdateRet(
                            "UPDATE " + Tables.playerData()
                                    + " SET last_server=?, online=1, logout_started_at=NULL"
                                    + " WHERE uuid=? AND (online=0 OR last_server=?)",
                            SELF, player_uuid, SELF);
                }
                if (claimed == 0) {
                    // Row exists (we checked in the poll) but the guard blocked us —
                    // meaning another server claimed between our poll read and our
                    // UPDATE. Defer to that winner and ask the player to retry.
                    PlayerSync.LOGGER.warn("Player {} claim CAS lost — another server claimed first; kicking this session", player_uuid);
                    SyncLogger.raceCondition(player_uuid, "Claim CAS lost — deferring to the winner");
                    server.execute(() -> {
                        if (serverPlayer.connection != null) {
                            serverPlayer.connection.disconnect(Component.translatableWithFallback(
                                    "playersync.claim_lost",
                                    "PlayerSync: another server is finalizing your save. Please reconnect in a few seconds."));
                        }
                    });
                    syncNotCompletedPlayer.remove(player_uuid);
                    return;
                }
            }

            // === PHASE 1: DB reads on background thread (thread-safe) ===

            // PERF (A8): single SELECT with explicit column list — covers both the
            // existence check (rs.next()==false means "new player, run init path") and
            // the full data read. Previously two separate SELECTs on the same row
            // produced two MySQL round-trips per join.
            // AUDIT FIX: `advancements` is deliberately EXCLUDED from the projection —
            // it is a MEDIUMBLOB (hundreds of KB to several MB on large modpacks) that
            // this path never consumes; onDataPackSyncEvent fetches it separately.
            // SELECT * transferred + compressed that dead column on every join.
            final int health, foodLevel, xp, score;
            final String leftHand, cursors, armorData, inventoryData, enderChestData, effectData;

            try (JDBCsetUp.QueryResult qr2 = JDBCsetUp.executePreparedQuery(
                    "SELECT health, food_level, xp, score, left_hand, cursors, armor, inventory, enderchest, effects FROM "
                            + Tables.playerData() + " WHERE uuid=?", player_uuid)) {
                ResultSet rs2 = qr2.resultSet();
                if (!rs2.next()) {
                    // No row in DB → brand new player.
                    // AUDIT FIX (main-thread I/O): the old init path ran store() —
                    // a synchronous INSERT plus one REPLACE per backpack/SS/RS2 item —
                    // entirely inside server.execute() on the MAIN thread (worst case
                    // 10s connectionTimeout stall per first-time joiner). Converted to
                    // the established pattern: DB pre-read here (BG), entity work +
                    // snapshot on main, DB writes back on the executor.
                    final String newPlayerCuriosData;
                    if (ModList.get().isLoaded("curios")) {
                        try (JDBCsetUp.QueryResult qr = JDBCsetUp.executePreparedQuery(
                                "SELECT curios_item FROM " + Tables.curios() + " WHERE uuid=?", player_uuid)) {
                            ResultSet rs = qr.resultSet();
                            newPlayerCuriosData = rs.next() ? rs.getString("curios_item") : null;
                        }
                    } else { newPlayerCuriosData = null; }

                    server.execute(() -> {
                        if (!isPlayerOnline(server, player_uuid)) {
                            syncNotCompletedPlayer.remove(player_uuid);
                            return;
                        }
                        try {
                            // Orphaned curios row (player_data wiped, curios kept):
                            // apply it to the entity so the init snapshot captures it.
                            if (newPlayerCuriosData != null) {
                                ModsSupport.applyCuriosFromData(serverPlayer, newPlayerCuriosData);
                            }

                            // MAIN THREAD: freeze entity state (fast copies, no DB).
                            final DeferredPlayerSnapshot frozen = snapshotPlayerData(serverPlayer);
                            final Map<UUID, CompoundTag> backpackSnapshots = ModsSupport.snapshotBackpackData(serverPlayer);
                            final Map<UUID, CompoundTag> ssSnapshots = ModsSupport.snapshotSSData(ModsSupport.collectSSUuids(serverPlayer));
                            final List<UUID> rs2DiskUuids;
                            final ServerLevel rs2Level;
                            final HolderLookup.Provider rs2Registry;
                            if (ModList.get().isLoaded("refinedstorage")) {
                                rs2DiskUuids = ModsSupport.collectRS2DiskUuids(serverPlayer);
                                rs2Level = serverPlayer.serverLevel();
                                rs2Registry = server.registryAccess();
                            } else {
                                rs2DiskUuids = List.of();
                                rs2Level = null;
                                rs2Registry = null;
                            }

                            // BACKGROUND: INSERT + mod writes. The player_synced tag is
                            // added only AFTER the INSERT lands — otherwise an instant
                            // disconnect would fire a logout save whose last_server-guarded
                            // UPDATE hits 0 rows (no row yet) and the first session is lost.
                            executorService.submit(() -> {
                                try {
                                    PlayerDataSnapshot s = frozen.materialize();
                                    PlayerSync.LOGGER.info("Storing data for new player {}", player_uuid);
                                    // INSERT IGNORE: idempotent against a quick
                                    // disconnect+reconnect racing two init paths.
                                    JDBCsetUp.executePreparedUpdate(
                                            "INSERT IGNORE INTO " + Tables.playerData()
                                                    + " (uuid, armor, inventory, enderchest, advancements, effects, xp, food_level, health, score, left_hand, cursors, online, last_server)"
                                                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)",
                                            s.uuid(), s.equipment(), s.inventory(), s.enderChest(),
                                            s.advancements() == null ? "" : s.advancements(), s.effects(),
                                            s.xp(), s.foodLevel(), s.health(), s.score(), s.leftHand(), s.cursors(),
                                            JdbcConfig.SERVER_ID.get());
                                    if (s.curiosData() != null) {
                                        JDBCsetUp.executePreparedUpdate(
                                                "REPLACE INTO " + Tables.curios() + " (uuid, curios_item) VALUES (?, ?)",
                                                s.uuid(), s.curiosData());
                                    }
                                    ModsSupport.saveBackpackSnapshots(backpackSnapshots);
                                    ModsSupport.saveSSSnapshots(ssSnapshots);
                                    if (!rs2DiskUuids.isEmpty() && rs2Level != null) {
                                        ModsSupport.saveRS2DisksByLevel(rs2DiskUuids, rs2Level, rs2Registry);
                                    }
                                    server.execute(() -> {
                                        if (isPlayerOnline(server, player_uuid)) {
                                            serverPlayer.addTag("player_synced");
                                        }
                                    });
                                } catch (Exception e) {
                                    PlayerSync.LOGGER.error("Error persisting new player {}", player_uuid, e);
                                } finally {
                                    syncNotCompletedPlayer.remove(player_uuid);
                                }
                            });
                        } catch (Exception e) {
                            PlayerSync.LOGGER.error("Error initializing new player {}", player_uuid, e);
                            syncNotCompletedPlayer.remove(player_uuid);
                        }
                    });
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

            // AUDIT FIX (N+1): single range scan on the (uuid, mod_id) PK instead of
            // three sequential round-trips (accessories / cosmeticarmor / attachments).
            // isLoaded gating preserved: a stale row for an uninstalled mod yields null
            // exactly as before; neoforge_attachments is read unconditionally.
            final String accessoriesData, cosmeticArmorData, attachmentsData;
            {
                String acc = null, cos = null, att = null;
                final boolean accessoriesLoaded = ModList.get().isLoaded("accessories");
                final boolean cosmeticLoaded = ModList.get().isLoaded("cosmeticarmorreworked");
                try (JDBCsetUp.QueryResult qr = JDBCsetUp.executePreparedQuery(
                        "SELECT mod_id, data_value FROM " + Tables.modPlayerData() + " WHERE uuid=?",
                        player_uuid)) {
                    ResultSet rs = qr.resultSet();
                    while (rs.next()) {
                        switch (rs.getString("mod_id")) {
                            case "accessories"          -> { if (accessoriesLoaded) acc = rs.getString("data_value"); }
                            case "cosmeticarmor"        -> { if (cosmeticLoaded)    cos = rs.getString("data_value"); }
                            case "neoforge_attachments" -> att = rs.getString("data_value");
                        }
                    }
                }
                accessoriesData = acc; cosmeticArmorData = cos; attachmentsData = att;
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

                    // === FIX DUP-REVIVE ===
                    // If the rejoining player is still in Revive Me's "fallen" (downed)
                    // state — or has already died during the join delay — DO NOT apply
                    // the DB data. The vanilla .dat file is the single source of truth
                    // for the player's items in this transient phase:
                    //
                    //  - Fallen player reconnecting: the .dat carries the exact inventory
                    //    they had when they fell. Restoring the DB copy here is what
                    //    caused the dup — Revive Me resumes the fall timer on reconnect,
                    //    the death finalizes, a corpse / gravestone mod captures the
                    //    inventory, and THEN this apply re-granted a second copy from DB.
                    //  - Player who died mid-join: their entity inventory was already
                    //    emptied by the death; re-applying the DB copy would dup with
                    //    the corpse.
                    //
                    // Skipping the apply leaves the .dat state untouched. When the player
                    // leaves the fallen state the normal sync resumes: a successful revive
                    // is captured by the next auto-save / logout-save; a finalized death is
                    // captured by onPlayerRespawn (empty inventory → DB). No dup, no loss.
                    final boolean joinFallen = isReviveMeFallen(serverPlayer);
                    final boolean joinDead = serverPlayer.isDeadOrDying();
                    if (joinFallen || joinDead) {
                        PlayerSync.LOGGER.info("[revive-detect] player {} joined in a fallen/dead state (fallen={}, deadOrDying={}) — skipping DB apply, keeping .dat inventory to avoid corpse dup",
                                player_uuid, joinFallen, joinDead);
                        SyncLogger.playerEvent(player_uuid, "JOIN_FALLEN_SKIP",
                                "Joined in ReviveMe fallen/dead state — DB apply skipped, vanilla .dat kept (corpse dup prevention)");
                        serverPlayer.addTag("player_synced");
                        return; // syncNotCompletedPlayer cleanup runs in the finally block
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

                    // PHASE 12 PERF: prefetch ALL storage UUIDs (backpacks + SS + RS2)
                    // in a single batched SELECT, then apply from the in-memory cache
                    // instead of making N sequential round-trips on the main thread.
                    // Shulker-heavy players see ~8-10× reduction in restore latency
                    // because backpack_data is shared across the three mod sources.
                    java.util.List<UUID> prefetchUuids = new java.util.ArrayList<>();
                    if (JdbcConfig.SYNC_BACKPACKS.get()) {
                        prefetchUuids.addAll(ModsSupport.collectBackpackUuids(serverPlayer, true));
                        if (ModList.get().isLoaded("sophisticatedstorage")) {
                            prefetchUuids.addAll(ModsSupport.collectSSUuids(serverPlayer));
                        }
                    }
                    if (JdbcConfig.SYNC_REFINED_STORAGE.get() && ModList.get().isLoaded("refinedstorage")) {
                        prefetchUuids.addAll(ModsSupport.collectRS2DiskUuids(serverPlayer));
                    }
                    if (!prefetchUuids.isEmpty()) {
                        java.util.Map<UUID, CompoundTag> prefetched = ModsSupport.prefetchStorageContents(prefetchUuids);
                        ModsSupport.setStoragePrefetchCache(prefetched);
                        PlayerSync.LOGGER.debug("[perf-restore] prefetched {}/{} storage UUIDs for player {}",
                                prefetched.size(), prefetchUuids.size(), player_uuid);
                    }
                    try {
                        // Backpacks/SS/RS2: restore methods now consume the prefetch cache
                        // (falls back to DB on cache miss — same behavior as before).
                        new ModsSupport().doBackPackRestore(serverPlayer);
                        if (ModList.get().isLoaded("sophisticatedstorage")) {
                            ModsSupport.restoreSophisticatedStorageItems(serverPlayer);
                        }
                        if (ModList.get().isLoaded("refinedstorage")) {
                            ModsSupport.restoreRefinedStorageDisks(serverPlayer);
                        }
                    } finally {
                        ModsSupport.clearStoragePrefetchCache();
                    }

                    serverPlayer.addTag("player_synced");
                    long totalRestore = System.currentTimeMillis() - restoreT0;
                    PlayerSync.LOGGER.info("Sync data for player {} completed in {}ms", player_uuid, totalRestore);
                    SyncLogger.restoreCompleted(player_uuid, totalRestore);
                    if (totalRestore > 1000) {
                        PlayerSync.LOGGER.warn("[perf-restore] slow restore for {} ({}ms) — enable log level=TRACE to profile",
                                player_uuid, totalRestore);
                    }
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
        CachedConnectCheck cachedEntry = connectCheckCache.remove(player_uuid);
        // PERF (A7): treat stale entries (> TTL) as cache miss → forces the safe fallback DB
        // path. Stops a stale 5-min-old entry from making the wrong kick decision.
        int[] cached = (cachedEntry != null && System.currentTimeMillis() - cachedEntry.insertedAt() <= CONNECT_CHECK_TTL_MS)
                ? cachedEntry.data() : null;

        if (!JdbcConfig.KICK_WHEN_ALREADY_ONLINE.get()) {
            // PHASE 14 FIX: do NOT pre-mark online=1 here. Previously this UPDATE ran on
            // the executor BEFORE doPlayerJoin's poll, overwriting a peer's freshly-committed
            // online=0 — the poll would then see online=1 + last_server=OldPeer and wait the
            // full 60s even though the peer had already flushed (observed in production logs
            // 2026-04-22 07:43:41 -> 07:45:01, 60s of 'Waiting for server X to finish saving'
            // when X had actually committed 19s earlier).
            // doPlayerJoin now sets online=1 atomically with last_server=self as part of its
            // claim UPDATE, after the poll has seen the true state.
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

            // PHASE 14 FIX: online=1 is no longer written here. See doPlayerJoin's claim
            // UPDATE for the replacement — setting the flag earlier raced the poll and
            // caused every cross-server join to wait the full 60s.
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

    /**
     * AUDIT FIX (security — log injection / disk flood): item NBT contains
     * player-authored content (anvil renames, book pages, mod data). SNBT escaping
     * does NOT escape raw newlines inside string tags, so logging it verbatim lets
     * a crafted book forge multi-line entries in latest.log — the same file that
     * carries [admin-dump]/[admin-wipe] audit records. Multi-hundred-KB payloads
     * also flooded the log on every join. This helper truncates to 256 chars and
     * neutralizes control characters before the payload reaches a logger.
     */
    private static String nbtPreview(String s) {
        if (s == null) return "null";
        String t = s.length() > 256 ? s.substring(0, 256) + "...(" + s.length() + " chars)" : s;
        return t.replaceAll("[\\r\\n]", "\\\\n").replaceAll("\\p{Cntrl}", "?");
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
                PlayerSync.LOGGER.warn("TagParser.parseTag failed, trying NbtUtils.snbtToStructure fallback ({} chars): {}",
                        nbtString.length(), nbtPreview(nbtString));
                try {
                    compoundTag = NbtUtils.snbtToStructure(nbtString);
                } catch (CommandSyntaxException e2) {
                    PlayerSync.LOGGER.error("Both SNBT parsers failed ({} chars): {}", nbtString.length(), nbtPreview(nbtString));
                    throw e; // re-throw original exception
                }
            }
        }

        if (compoundTag.isEmpty() || !compoundTag.contains("id", Tag.TAG_STRING)) {
            return ItemStack.EMPTY; // Invalid or empty tag
        }

        ResourceLocation registryName = ResourceLocation.tryParse(compoundTag.getString("id"));

        if (registryName == null) {
            PlayerSync.LOGGER.warn("Failed to parse registry name from NBT ({} chars): {}", nbtString.length(), nbtPreview(nbtString));
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
                        "ItemStack.of returned EMPTY for known item {} ({} chars NBT). Creating placeholder as fallback.",
                        registryName, nbtString.length());
            } catch (Exception e) {
                PlayerSync.LOGGER.error(
                        "Error creating ItemStack for known item {} ({} chars NBT): {}. Creating placeholder as fallback.",
                        registryName, nbtString.length(), nbtPreview(nbtString), e);
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
        // AUDIT FIX: removed the unconditional WARN that dumped the FULL compound tag
        // for every placeholder item on every join/restore (log-flood + injection vector).
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
     * PHASE 19: optional save on respawn — gated by {@code save_on_respawn}.
     * Runs AFTER the respawn is complete so the snapshot captures the final
     * post-death inventory (vanilla drops + whatever keeping-charms preserved).
     * This OVERWRITES the pre-death snapshot taken in onPlayerDeath with the
     * correct authoritative state, so the next restore sees the real inventory.
     *
     * <p>Essential when mods like Twilight Forest's Charm of Keeping or
     * Corail Tombstone restore items on respawn — without this event,
     * PlayerSync's DB row stays at the pre-death snapshot until the next
     * auto-save, and a quick disconnect loses the keep-charm state.
     */
    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        try {
            if (!JdbcConfig.SAVE_ON_RESPAWN.get()) return;
            if (event.isEndConquered()) return; // End-portal exit, not a death respawn
            Player player = event.getEntity();
            SyncLogger.playerEvent(player.getUUID().toString(), "RESPAWN",
                    "Snapshot post-respawn inventory (keeping-charm / tombstone mods)");
            snapshotAndQueueSave(player, "RESPAWN");
        } catch (Exception e) {
            PlayerSync.LOGGER.warn("[respawn-save] trigger failed: {}", e.getMessage());
        }
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
            // === MAIN THREAD: FREEZE entity state into ItemStack copies (no serialization yet) ===
            final DeferredPlayerSnapshot frozen = snapshotPlayerData(player);

            // === BACKGROUND THREAD: serialize + all DB writes — main thread continues immediately ===
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
                    // PHASE 18: heavy NBT serialization now happens HERE on BG, not main.
                    PlayerDataSnapshot snapshot = frozen.materialize();
                    // PHASE 7 PERF: skip write when snapshot hashes identical to last-written.
                    int newHash = computeSnapshotHash(snapshot);
                    Integer prev = lastWrittenSnapshotHash.get(puuid);
                    if (prev != null && prev == newHash) {
                        return; // identical — no DB write needed
                    }
                    if (writeSnapshotToDB(snapshot)) {
                        lastWrittenSnapshotHash.put(puuid, newHash);
                    }
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
        // Now: snapshot 35 players (~50ms total), then up to 16 concurrent DB writes
        // (executor width — see the AUDIT FIX on the pool config above).
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
                    // PHASE 18: returns DeferredPlayerSnapshot — item NBT serialization happens on BG.
                    final DeferredPlayerSnapshot frozen = snapshotPlayerData(player);
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
                        long t0 = System.currentTimeMillis();
                        try {
                            PlayerDataSnapshot snapshot = frozen.materialize();
                            boolean persisted = writeSnapshotToDB(snapshot, true);
                            if (persisted) {
                                ModsSupport.saveBackpackSnapshots(backpackSnapshots);
                                ModsSupport.saveSSSnapshots(ssSnapshots);
                                if (!rs2DiskUuids.isEmpty() && rs2Level != null) {
                                    ModsSupport.saveRS2DisksByLevel(rs2DiskUuids, rs2Level, rs2Registry);
                                }
                                long dur = System.currentTimeMillis() - t0;
                                PlayerSync.LOGGER.info("Saved player {} data on server shutdown in {}ms", puuid, dur);
                                SyncLogger.saveCompleted(puuid, "SHUTDOWN", dur);
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
        // Phase 5: stop pool-stats reporter.
        vip.fubuki.playersync.util.PoolStatsReporter.stop();

        // Shut down the background executor — no new tasks after this point
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException ignored) {
            executorService.shutdownNow();
        }
        // Drain the overflow lane too (rarely holds anything — only queue-overflow spill).
        overflowExecutor.shutdown();
        try {
            if (!overflowExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                overflowExecutor.shutdownNow();
            }
        } catch (InterruptedException ignored) {
            overflowExecutor.shutdownNow();
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
                    final DeferredPlayerSnapshot frozen = snapshotPlayerData(player);
                    final Map<UUID, CompoundTag> backpackSnapshots = ModsSupport.snapshotBackpackData(player);
                    final Map<UUID, CompoundTag> ssSnapshots = ModsSupport.snapshotSSData(ModsSupport.collectSSUuids(player));
                    // Direct synchronous write (no executor, no lock) — materialize inline.
                    PlayerDataSnapshot snapshot = frozen.materialize();
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

        // === FIX DUP-REVIVE: disconnect while fallen (ReviveMe downed) or dead ===
        // Decompiling revive_me-1.21.1-5.7.14 + corpse-neoforge-1.1.13 revealed the
        // exact mechanism:
        //   - ReviveMe.CapabilityEvents.onLogout (NORMAL priority) runs on disconnect:
        //     for a fallen player it pauses the fall timer, removes effects, and — when
        //     the config dieOnDisconnect is true — calls FallenData.forceDeath().
        //   - forceDeath() applies lethal damage → LivingDeathEvent → LivingDropsEvent.
        //   - Corpse mod (DeathEvents) creates the corpse on LivingDropsEvent; its
        //     curios / cosmetic compat mods pull those items into the corpse too.
        // So on a disconnect from the fallen state the player's items are bound for a
        // corpse. If PlayerSync's normal logout-save wrote the still-attached inventory
        // (it runs at NORMAL priority — ordering vs ReviveMe's onLogout is undefined),
        // the rejoining player would get the DB copy AND the corpse would hold one =
        // duplication.
        //
        // Detection is EXACT (no heuristics): the player is fallen iff Revive Me's
        // FallenData.isFallen() says so; or already dead iff isDeadOrDying(). Either
        // state — regardless of whether PlayerSync's handler ran before or after
        // ReviveMe's forceDeath — means the items are corpse-bound. We clear every
        // item-bearing DB column (inventory / armor / left_hand / cursors / curios /
        // accessories / cosmetic_armor) so the corpse is the single source of truth.
        //
        // The matching JOIN-side guard (doPlayerJoin skips the data apply when the
        // rejoining player is still fallen) ensures the dieOnDisconnect=false path —
        // where the player reconnects STILL fallen — keeps their vanilla .dat
        // inventory instead of having it overwritten by this cleared DB row.
        //
        // keepInventory game rule: when ON, a death drops NOTHING and forms NO corpse
        // — the items stay on the player. Clearing the DB then would DESTROY them. So
        // the clear is gated on keepInventory being OFF; with it ON the normal save
        // path runs and the player keeps their inventory across the death.
        if (event.getEntity() instanceof ServerPlayer fallenOrDead) {
            // AUDIT FIX (perf): evaluate the fallen/dead state ONCE and pass the
            // booleans down — handleFallenLogout previously re-invoked the reflective
            // isReviveMeFallen just for its log line.
            final boolean logoutFallen = isReviveMeFallen(fallenOrDead);
            final boolean logoutDead = fallenOrDead.isDeadOrDying();
            if (logoutFallen || logoutDead) {
                boolean keepInv;
                try {
                    keepInv = fallenOrDead.serverLevel().getGameRules()
                            .getBoolean(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY);
                } catch (Exception e) {
                    keepInv = false; // unreadable → err toward clearing (dup is the reported bug)
                }
                if (!keepInv) {
                    handleFallenLogout(fallenOrDead, player_uuid, logoutFallen, logoutDead);
                    return;
                }
                PlayerSync.LOGGER.info("[revive-detect] player {} disconnecting fallen/dead but keepInventory=ON — normal save (items stay with player, no corpse)", player_uuid);
            }
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
                SyncLogger.containerForceClosed(player_uuid, "self container on logout");
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
                // PHASE 18 PERF: fast-path early return when no other player has a non-own-inventory
                // menu open. On an empty server or one where nobody is looking at someone else's
                // stuff, this saves iterating the player list + slots per logout.
                boolean anyOtherWithForeignMenu = false;
                for (ServerPlayer other : disconnecting.getServer().getPlayerList().getPlayers()) {
                    if (other == disconnecting) continue;
                    if (other.containerMenu != other.inventoryMenu) { anyOtherWithForeignMenu = true; break; }
                }
                if (anyOtherWithForeignMenu) {
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
                            try {
                                other.closeContainer();
                                SyncLogger.containerForceClosed(player_uuid,
                                        "viewer " + other.getUUID() + " had a menu referencing disconnecting player's inv/enderchest");
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }

            // === MAIN THREAD: Snapshot ALL entity state (fast, no DB I/O) ===
            if (ModList.get().isLoaded("curios") && !player.isDeadOrDying()) {
                CuriosCache.tryStoreCuriosToCache((ServerPlayer) player);
            }

            // PHASE 18: freeze on main thread (fast copies), materialize on BG.
            final DeferredPlayerSnapshot frozen = snapshotPlayerData(player);

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

            // PHASE 15: mark logout-in-progress for cross-server visibility. Joining servers
            // read this column to distinguish 'peer saving' from 'ghost session' — a fresh
            // timestamp here means we're committing shortly, a stale or NULL value means
            // either no save in progress (clean/new player) or the save thread died. The
            // async save clears this atomically with online=0 when it commits.
            try {
                JDBCsetUp.executePreparedUpdate(
                        "UPDATE " + Tables.playerData() + " SET logout_started_at=? WHERE uuid=?",
                        System.currentTimeMillis(), player_uuid);
            } catch (Exception e) {
                PlayerSync.LOGGER.warn("[phase15] could not mark logout_started_at for {}: {}", player_uuid, e.getMessage());
            }

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
                    // PHASE 10 OBSERVABILITY: measure every stage so sync.log shows REAL
                    // durations instead of hardcoded 0ms. Helps diagnose user-reported
                    // 20s latencies: we can see which stage actually takes the time.
                    final long t0 = System.currentTimeMillis();
                    // PHASE 18: heavy NBT serialization runs on BG, not main thread.
                    PlayerDataSnapshot snapshot = frozen.materialize();
                    boolean persisted = writeSnapshotToDB(snapshot, true);
                    final long tCore = System.currentTimeMillis();
                    if (persisted) {
                        lastWrittenSnapshotHash.put(player_uuid, computeSnapshotHash(snapshot));
                        ModsSupport.saveBackpackSnapshots(backpackSnapshots);
                        final long tBp = System.currentTimeMillis();
                        ModsSupport.saveSSSnapshots(ssSnapshots);
                        final long tSs = System.currentTimeMillis();
                        if (!rs2DiskUuids.isEmpty() && rs2Level != null) {
                            ModsSupport.saveRS2DisksByLevel(rs2DiskUuids, rs2Level, rs2RegistryAccess);
                        }
                        final long tEnd = System.currentTimeMillis();
                        long total = tEnd - t0;
                        PlayerSync.LOGGER.info("Logout save completed for player {} in {}ms", player_uuid, total);
                        SyncLogger.saveCompleted(player_uuid, "LOGOUT", total);
                        SyncLogger.perf("LOGOUT breakdown [" + player_uuid + "]",
                                (tCore - t0));
                        if (total > 200) {
                            String detail = "core=" + (tCore - t0) + "ms backpacks=" + (tBp - tCore)
                                    + "ms ss=" + (tSs - tBp) + "ms rs2=" + (tEnd - tSs) + "ms total=" + total + "ms";
                            PlayerSync.LOGGER.info("[perf-logout] {} {}", player_uuid, detail);
                            // PHASE 11: also log to sync.log so field reports don't miss the breakdown.
                            SyncLogger.perf("LOGOUT " + player_uuid + " " + detail, total);
                        }
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

    /**
     * FIX DUP-REVIVE: logout path for a player disconnecting while in Revive Me's
     * "fallen" state or already dead. The items are bound for a corpse (Revive Me's
     * forceDeath on dieOnDisconnect=true, or the post-disconnect revive-timer death
     * on dieOnDisconnect=false), so the DB item columns are explicitly cleared via
     * {@link #writeReviveLogoutClearItemsToDB} — the corpse becomes the single source
     * of truth and a later restore can no longer dup with it.
     *
     * <p>Mirrors the normal logout-save's structure (per-player lock, pendingLogoutSaves
     * future, logout_started_at marker, bg executor) so every cross-server race guard
     * still holds. Backpack / SS / RS2 snapshots are intentionally NOT captured: those
     * stores are keyed by item UUID and follow the item into the corpse.
     */
    private static void handleFallenLogout(ServerPlayer player, String player_uuid, boolean fallen, boolean dead) {
        PlayerSync.LOGGER.info("[revive-detect] player {} disconnecting while fallen/dead (fallen={}, deadOrDying={}) — clearing DB item columns to prevent corpse dup",
                player_uuid, fallen, dead);
        SyncLogger.playerEvent(player_uuid, "LOGOUT_FALLEN",
                "Disconnect while fallen/dead — DB item columns cleared (corpse dup prevention)");

        ReentrantLock lock = getPlayerLock(player_uuid);
        lock.lock();
        CompletableFuture<Void> saveFuture = null;
        try {
            // Snapshot non-item progression. The deferred item arrays are captured too
            // (snapshotPlayerData has no non-item variant) but writeReviveLogoutClearItemsToDB
            // ignores them and writes empty item columns instead.
            final DeferredPlayerSnapshot frozen = snapshotPlayerData(player);

            saveFuture = new CompletableFuture<>();
            pendingLogoutSaves.put(player_uuid, saveFuture);

            try {
                JDBCsetUp.executePreparedUpdate(
                        "UPDATE " + Tables.playerData() + " SET logout_started_at=? WHERE uuid=?",
                        System.currentTimeMillis(), player_uuid);
            } catch (Exception e) {
                PlayerSync.LOGGER.warn("[revive-logout] could not mark logout_started_at for {}: {}", player_uuid, e.getMessage());
            }

            final CompletableFuture<Void> futureRef = saveFuture;
            try {
                executorService.execute(() -> {
                    ReentrantLock bgLock = getPlayerLock(player_uuid);
                    bgLock.lock();
                    try {
                        long t0 = System.currentTimeMillis();
                        PlayerDataSnapshot snapshot = frozen.materialize();
                        boolean persisted = writeReviveLogoutClearItemsToDB(snapshot);
                        long total = System.currentTimeMillis() - t0;
                        if (persisted) {
                            // Invalidate the hash cache so a pending auto-save BG cannot
                            // resurrect the cleared inventory via the hash-skip shortcut.
                            lastWrittenSnapshotHash.remove(player_uuid);
                            PlayerSync.LOGGER.info("Fallen-logout completed for player {} in {}ms (item columns cleared)", player_uuid, total);
                            SyncLogger.saveCompleted(player_uuid, "LOGOUT_FALLEN", total);
                        } else {
                            PlayerSync.LOGGER.warn("Fallen-logout: core write blocked for {} (another server claimed)", player_uuid);
                            SyncLogger.saveSkipped(player_uuid, "LOGOUT_FALLEN", "core guard blocked");
                        }
                    } catch (Exception e) {
                        PlayerSync.LOGGER.error("Error during fallen-logout save for {}", player_uuid, e);
                        SyncLogger.saveFailed(player_uuid, "LOGOUT_FALLEN", e.getMessage());
                        try {
                            JDBCsetUp.executePreparedUpdate(
                                    "UPDATE " + Tables.playerData() + " SET online=0, logout_started_at=NULL WHERE uuid=? AND last_server=?",
                                    player_uuid, JdbcConfig.SERVER_ID.get());
                        } catch (Exception ignored) {}
                    } finally {
                        removePlayerLock(player_uuid);
                        pendingLogoutSaves.remove(player_uuid);
                        futureRef.complete(null);
                        try { bgLock.unlock(); } catch (Exception ignored) {}
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException rex) {
                PlayerSync.LOGGER.warn("Fallen-logout executor rejected task for player {} (likely shutdown in progress)", player_uuid);
                pendingLogoutSaves.remove(player_uuid);
                futureRef.completeExceptionally(rex);
                removePlayerLock(player_uuid);
            }
        } catch (Exception e) {
            PlayerSync.LOGGER.error("Error during fallen-logout for {}", player_uuid, e);
            try {
                JDBCsetUp.executePreparedUpdate(
                        "UPDATE " + Tables.playerData() + " SET online=0, logout_started_at=NULL WHERE uuid=? AND last_server=?",
                        player_uuid, JdbcConfig.SERVER_ID.get());
            } catch (Exception ignored) {}
            removePlayerLock(player_uuid);
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
     * AUDIT FIX (security — zip bomb): heap quota for decompressed NBT read from the
     * DB. unlimitedHeap() allowed a crafted/corrupted row (gzip ratios up to ~1000×)
     * to allocate multi-GB tags and OOM the server. 64 MB is far beyond any
     * legitimate item / backpack payload (write side already caps the serialized
     * form at max_inventory_size_bytes, 10 MB default).
     */
    private static final long MAX_DECOMPRESSED_NBT_BYTES = 64L * 1024 * 1024;

    /**
     * Deserializes a Base64-encoded binary NBT string back to a CompoundTag.
     */
    public static CompoundTag deserializeBinaryBase64Tag(String encoded) throws IOException {
        String base64 = encoded.substring(5); // Remove "BNBT:" prefix
        byte[] bytes = Base64.getDecoder().decode(base64);
        java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(bytes);
        return net.minecraft.nbt.NbtIo.readCompressed(bais, net.minecraft.nbt.NbtAccounter.create(MAX_DECOMPRESSED_NBT_BYTES));
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
     * PHASE 18: frozen ItemStack copies captured on main thread; item NBT
     * serialization is deferred to the BG write task. Saves 100-250ms of
     * main-thread CPU per logout for a full inventory (69+ items × NBT→SNBT→
     * Base64 previously ran synchronously during PlayerLoggedOutEvent).
     *
     * <p>ItemStack.copy() is O(1) component clone + count snapshot — safe to
     * hand to another thread because components are effectively immutable
     * (modifications create a new ItemStack via a setter, not in-place mutation).
     *
     * <p>Curios / accessories / cosmetic / effects / attachments / advancements
     * are still pre-serialized on main thread: they either require live entity
     * access (main-thread only in NeoForge) or are small enough that deferring
     * is overkill.
     */
    record DeferredPlayerSnapshot(
            String uuid, int xp, int score, int foodLevel, int health,
            String effects, String advancements,
            String curiosData, String accessoriesData, String cosmeticArmorData, String attachmentsData,
            // Deferred — ItemStack copies, serialized to strings on BG via materialize()
            ItemStack leftHand, ItemStack cursors,
            ItemStack[] armor, ItemStack[] inventory, ItemStack[] enderChest
    ) {
        /** Serializes all deferred ItemStack arrays. Runs on the caller's thread — typically BG. */
        PlayerDataSnapshot materialize() {
            String leftHandStr = getNbtForStorage(leftHand);
            String cursorsStr  = getNbtForStorage(cursors);

            Map<Integer, String> armorMap = new HashMap<>(armor.length);
            for (int i = 0; i < armor.length; i++) armorMap.put(i, getNbtForStorage(armor[i]));

            Map<Integer, String> inventoryMap = new HashMap<>(inventory.length);
            for (int i = 0; i < inventory.length; i++) inventoryMap.put(i, getNbtForStorage(inventory[i]));

            Map<Integer, String> enderChestMap = new HashMap<>(enderChest.length);
            for (int i = 0; i < enderChest.length; i++) enderChestMap.put(i, getNbtForStorage(enderChest[i]));

            return new PlayerDataSnapshot(
                    uuid, xp, score, foodLevel, health,
                    leftHandStr, cursorsStr,
                    armorMap.toString(), inventoryMap.toString(), enderChestMap.toString(), effects,
                    advancements,
                    curiosData, accessoriesData, cosmeticArmorData, attachmentsData
            );
        }
    }

    /**
     * Captures all player data into an immutable snapshot on the MAIN THREAD.
     * PHASE 18: returns a {@link DeferredPlayerSnapshot} where the item arrays
     * are frozen via {@link ItemStack#copy()} but NOT yet serialized. The heavy
     * NBT→SNBT→Base64 work (dozens of items × several ms each) happens later
     * when the BG task calls {@code materialize()}.
     *
     * <p>Main-thread cost drops from ~200-300ms to ~20-50ms for a full inventory.
     */
    private static DeferredPlayerSnapshot snapshotPlayerData(Player player) throws Exception {
        String uuid = player.getUUID().toString();
        int XP = getTotalExperience(player);
        int score = player.getScore();
        int foodLevel = player.getFoodData().getFoodLevel();
        int health = (int) player.getHealth();

        // PHASE 18: copy ItemStacks (fast component clone — no NBT serialization yet).
        ItemStack leftHandStack = player.getItemInHand(net.minecraft.world.InteractionHand.OFF_HAND).copy();
        ItemStack cursorsStack  = player.containerMenu.getCarried().copy();

        int armorSize = player.getInventory().armor.size();
        ItemStack[] armor = new ItemStack[armorSize];
        for (int i = 0; i < armorSize; i++) armor[i] = player.getInventory().armor.get(i).copy();

        int invSize = player.getInventory().items.size();
        ItemStack[] inventory = new ItemStack[invSize];
        for (int i = 0; i < invSize; i++) inventory[i] = player.getInventory().items.get(i).copy();

        int enderSize = player.getEnderChestInventory().getContainerSize();
        ItemStack[] enderChest = new ItemStack[enderSize];
        for (int i = 0; i < enderSize; i++) enderChest[i] = player.getEnderChestInventory().getItem(i).copy();
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

        // PHASE 17 PERF: advancements file read — main-thread I/O was ~10-50ms per
        // snapshot on mechanical disk / slow network mount. Cache the content by
        // (absolute path + last-modified timestamp); reuse the cached string if
        // neither changed since the last snapshot. Minecraft's advancement save
        // only writes the file when something actually changed, so mtime is a
        // reliable freshness signal. PlayerAdvancements.save() is still called
        // to flush pending changes to disk.
        String advancements = null;
        if (JdbcConfig.SYNC_ADVANCEMENTS.get() && player instanceof ServerPlayer sp) {
            try { sp.getAdvancements().save(); } catch (Exception ignored) {}
            Path path = sp.getServer().getServerDirectory().resolve(getSyncWorldForServer());
            File advFile = new File(path.toFile(), "/advancements/" + uuid + ".json");
            if (advFile.exists()) {
                String absPath = advFile.getAbsolutePath();
                long mtime = advFile.lastModified();
                AdvancementsCacheEntry cached = advancementsFileCache.get(absPath);
                if (cached != null && cached.mtime == mtime && cached.content != null) {
                    advancements = cached.content;
                } else {
                    String content = new String(Files.readAllBytes(advFile.toPath()), StandardCharsets.UTF_8);
                    if (content != null && !content.isEmpty()) {
                        advancements = content;
                        advancementsFileCache.put(absPath, new AdvancementsCacheEntry(mtime, content));
                    }
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

        return new DeferredPlayerSnapshot(
                uuid, XP, score, foodLevel, health,
                effectMap.toString(), advancements,
                curiosData, accessoriesData, cosmeticArmorData, attachmentsData,
                leftHandStack, cursorsStack, armor, inventory, enderChest
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

        // PHASE 8: safety guards — abort before corrupting DB with garbage or wipes.
        if (JdbcConfig.REFUSE_EMPTY_INVENTORY_WRITE.get()
                && (s.inventory() == null || s.inventory().isEmpty() || s.inventory().length() < 4)) {
            // Only skip if DB currently has real data — new players legitimately have empty inventories
            try (JDBCsetUp.QueryResult qr = JDBCsetUp.executePreparedQuery(
                    "SELECT LENGTH(inventory) AS len FROM " + Tables.playerData() + " WHERE uuid=?", s.uuid())) {
                ResultSet rs = qr.resultSet();
                if (rs.next() && rs.getInt("len") > 50) {
                    SyncLogger.dataLoss(s.uuid(),
                            "REFUSED empty inventory write (DB has " + rs.getInt("len") + " bytes). Set refuse_empty_inventory_write=false to override.");
                    PlayerSync.LOGGER.warn("[write-guard] refused empty inventory write for {} (DB has {} bytes)",
                            s.uuid(), rs.getInt("len"));
                    return false;
                }
            } catch (Exception ignored) {}
        }
        int maxBytes = JdbcConfig.MAX_INVENTORY_SIZE_BYTES.get();
        if (s.inventory() != null && s.inventory().length() > maxBytes) {
            SyncLogger.nbtAnomaly(s.uuid(),
                    "inventory payload " + s.inventory().length() + " bytes exceeds max_inventory_size_bytes=" + maxBytes + " — REJECTED");
            PlayerSync.LOGGER.error("[write-guard] inventory too large for {} ({} bytes > {} max)",
                    s.uuid(), s.inventory().length(), maxBytes);
            return false;
        }

        // FIX PERF: All writes batched into a SINGLE transaction on ONE connection.
        // Previously 4-8 separate connections × round-trips per player.
        // Now: 1 connection, 1 commit, automatic rollback on failure.
        String serverGuard = "(last_server=? OR last_server IS NULL)";
        String coreSql = setOffline
                // PHASE 15: atomic clear of logout_started_at when the logout save commits.
                // Joining servers see logout_started_at=NULL + online=0 = clean, take over instantly.
                ? "UPDATE " + Tables.playerData() + " SET inventory=?, armor=?, xp=?, effects=?, enderchest=?, score=?, food_level=?, health=?, advancements=COALESCE(?, advancements), left_hand=?, cursors=?, online=0, last_server=?, logout_started_at=NULL WHERE uuid=? AND " + serverGuard
                : "UPDATE " + Tables.playerData() + " SET inventory=?, armor=?, xp=?, effects=?, enderchest=?, score=?, food_level=?, health=?, advancements=COALESCE(?, advancements), left_hand=?, cursors=?, last_server=? WHERE uuid=? AND " + serverGuard;

        // Build batch of all statements
        List<Object[]> batch = new ArrayList<>();

        // 1. Core player data
        batch.add(new Object[]{coreSql,
                s.inventory(), s.equipment(), s.xp(), s.effects(), s.enderChest(), s.score(), s.foodLevel(), s.health(), s.advancements(), s.leftHand(), s.cursors(), serverId, s.uuid(), serverId});

        // 2. Curios
        // AUDIT FIX (batching): single guarded upsert instead of UPDATE + INSERT IGNORE
        // pair — halves the statements and binds the (potentially large) blob ONCE.
        // The INSERT...SELECT keeps the last_server guard: 0 rows selected = no write.
        if (s.curiosData() != null) {
            batch.add(new Object[]{
                    "INSERT INTO " + Tables.curios() + " (uuid, curios_item) SELECT ?, ? FROM " + Tables.playerData()
                            + " WHERE uuid=? AND " + serverGuard
                            + " ON DUPLICATE KEY UPDATE curios_item=VALUES(curios_item)",
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
        // AUDIT FIX (batching): single guarded upsert per mod_id (PK is (uuid, mod_id))
        // instead of UPDATE + INSERT IGNORE pair. The batch drops from 9 to 5 statements
        // per save and each data_value blob is bound once instead of twice.
        batch.add(new Object[]{
                "INSERT INTO " + Tables.modPlayerData() + " (uuid, mod_id, data_value) SELECT ?, ?, ? FROM " + Tables.playerData()
                        + " WHERE uuid=? AND " + serverGuard
                        + " ON DUPLICATE KEY UPDATE data_value=VALUES(data_value)",
                uuid, modId, data, uuid, serverId});
    }

    /** Backwards-compatible overload for periodic saves (no offline flag). */
    private static boolean writeSnapshotToDB(PlayerDataSnapshot s) throws Exception {
        return writeSnapshotToDB(s, false);
    }

    /**
     * FIX DUP-REVIVE: write path used when a player disconnects while in Revive Me's
     * "fallen" state OR already dead. Persists progression (xp / effects / score /
     * food / health / advancements) AND explicitly clears every item-bearing column
     * that would otherwise dup with the corpse the corpse/gravestone mod forms:
     *
     * <ul>
     *   <li>{@code player_data} — inventory / armor / left_hand / cursors</li>
     *   <li>{@code curios} — curios_item (caught into the corpse by corpsecurioscompat)</li>
     *   <li>{@code mod_player_data} where {@code mod_id IN ('accessories','cosmeticarmor')}
     *       (Accessories slots used by The Aether; Cosmetic Armor Reworked, caught by
     *       cosmeticcorpsecompat)</li>
     * </ul>
     *
     * <p>NOT touched: {@code enderchest} (does not drop on death), backpack/SS/RS2
     * (keyed by ITEM UUID — the item itself drops into the corpse with its data), and
     * {@code mod_id='neoforge_attachments'} (per-player progression: Aether portals /
     * darts / flight / life-shards, Apotheosis world tier, Ars Nouveau / Iron's
     * Spellbooks mana, etc. — never lost on death, MUST persist).
     *
     * <p>online=0 + logout_started_at=NULL are set atomically in the core UPDATE.
     * Bypasses {@code refuse_empty_inventory_write} — the empty write is intentional.
     *
     * @return true if the core UPDATE persisted, false if the last_server guard blocked.
     */
    private static boolean writeReviveLogoutClearItemsToDB(PlayerDataSnapshot s) throws Exception {
        int serverId = JdbcConfig.SERVER_ID.get();
        String serverGuard = "(last_server=? OR last_server IS NULL)";
        // "{}" — canonical empty-map encoding; LocalJsonUtil.StringToEntryMap and the
        // apply*FromData functions skip restoration when data.length() <= 2.
        // "B64:e30=" — canonical empty-item encoding (Base64 of "{}").
        final String emptyMap = "{}";
        final String emptyItem = "B64:e30=";

        List<Object[]> batch = new ArrayList<>();

        String coreSql = "UPDATE " + Tables.playerData()
                + " SET inventory=?, armor=?, left_hand=?, cursors=?,"
                + "     xp=?, effects=?, score=?, food_level=?, health=?,"
                + "     advancements=COALESCE(?, advancements),"
                + "     online=0, last_server=?, logout_started_at=NULL"
                + " WHERE uuid=? AND " + serverGuard;
        batch.add(new Object[]{coreSql,
                emptyMap, emptyMap, emptyItem, emptyItem,
                s.xp(), s.effects(), s.score(), s.foodLevel(), s.health(),
                s.advancements(), serverId,
                s.uuid(), serverId});

        String curioGuard = "EXISTS (SELECT 1 FROM " + Tables.playerData()
                + " WHERE uuid=? AND " + serverGuard + ")";
        batch.add(new Object[]{
                "UPDATE " + Tables.curios() + " SET curios_item=? WHERE uuid=? AND " + curioGuard,
                emptyMap, s.uuid(), s.uuid(), serverId});

        String modDataGuard = "EXISTS (SELECT 1 FROM " + Tables.playerData()
                + " WHERE uuid=? AND " + serverGuard + ")";
        batch.add(new Object[]{
                "UPDATE " + Tables.modPlayerData() + " SET data_value=?"
                        + " WHERE uuid=? AND mod_id=? AND " + modDataGuard,
                emptyMap, s.uuid(), "accessories", s.uuid(), serverId});
        batch.add(new Object[]{
                "UPDATE " + Tables.modPlayerData() + " SET data_value=?"
                        + " WHERE uuid=? AND mod_id=? AND " + modDataGuard,
                emptyMap, s.uuid(), "cosmeticarmor", s.uuid(), serverId});

        int[] counts = JDBCsetUp.executeBatchTransaction(batch.toArray(new Object[0][]));
        if (counts.length > 0 && counts[0] == 0) {
            SyncLogger.guardBlocked(s.uuid(), serverId,
                    "revive-logout clear-items UPDATE affected 0 rows — last_server mismatch");
            return false;
        }
        return true;
    }

    /**
     * FIX DUP-DEATH: writes ONLY non-item progression fields (XP, food, score, health,
     * effects, advancements). Used by the death-save to preserve recent progress as a
     * safety net for server crashes between death and logout, WITHOUT touching the
     * inventory.
     *
     * <p>Why this matters: when a corpse / gravestone mod is loaded, items dropped on
     * death are persisted in the corpse entity / block forever. If the death-save wrote
     * the pre-death inventory to the DB, it could win a race against the logout-save's
     * post-death snapshot (empty) and the player would rejoin with their full inventory
     * AND a corpse still holding the items — duplication.
     *
     * <p>Inventory / armor / enderchest / left-hand / cursors / curios / accessories /
     * cosmetic armor / backpacks / SS / RS2 are all handled exclusively by the
     * logout-save (or shutdown-save), which captures post-drop state.
     *
     * @return true if the core write affected ≥ 1 row, false if the last_server guard
     *         blocked it (a non-fatal no-op).
     */
    private static boolean writeNonItemSnapshotToDB(PlayerDataSnapshot s) throws Exception {
        int serverId = JdbcConfig.SERVER_ID.get();
        String serverGuard = "(last_server=? OR last_server IS NULL)";
        String sql = "UPDATE " + Tables.playerData()
                + " SET xp=?, effects=?, score=?, food_level=?, health=?,"
                + "     advancements=COALESCE(?, advancements), last_server=?"
                + " WHERE uuid=? AND " + serverGuard;
        int[] counts = JDBCsetUp.executeBatchTransaction(new Object[][]{
                new Object[]{sql,
                        s.xp(), s.effects(), s.score(), s.foodLevel(), s.health(),
                        s.advancements(), serverId,
                        s.uuid(), serverId}
        });
        if (counts.length > 0 && counts[0] == 0) {
            SyncLogger.guardBlocked(s.uuid(), serverId,
                    "death-save non-item UPDATE affected 0 rows — last_server mismatch");
            return false;
        }
        return true;
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

    /**
     * PHASE 18: public entry point for PeriodicSaveService to enqueue all online
     * players for the SAME staggered 1-player/tick drain as the vanilla auto-save.
     * Previously PeriodicSaveService called {@code snapshotAndQueueSave} for every
     * player in a single {@code server.execute}, dumping 35 snapshots into one tick
     * and causing the observable lag spike. This unifies both pathways behind the
     * existing {@link #onServerTick} staggered drain.
     *
     * <p>Must be called from the main thread (mutates the shared queue).
     * Deduplicates against the current queue so overlapping triggers don't double-
     * enqueue a player.
     */
    public static void enqueueAllOnlineForStaggeredSave(MinecraftServer server) {
        if (server == null) return;
        // Build a quick lookup of current queue UUIDs (the queue is typically small).
        java.util.Set<UUID> already = new java.util.HashSet<>(autoSaveQueue.size());
        for (ServerPlayer p : autoSaveQueue) already.add(p.getUUID());
        int added = 0;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!already.contains(p.getUUID())) {
                autoSaveQueue.add(p);
                added++;
            }
        }
        if (added > 0) {
            PlayerSync.LOGGER.debug("[periodic-save] enqueued {} players for staggered save (queue size={})", added, autoSaveQueue.size());
        }
    }
    private static int autoCleanCuriosCacheTickCounter = 0;
    private static final int AUTO_CLEAN_CURIOS_CACHE_INTERVAL_TICKS = 36000; // Every 30 min

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        heartbeatTickCounter++;
        autoSaveTickCounter++;
        autoCleanCuriosCacheTickCounter++;

        // PERF (A7): every 600 ticks (~30 s) drop any connectCheckCache entry older
        // than CONNECT_CHECK_TTL_MS. Stops the map from leaking when a player triggers
        // PlayerNegotiationEvent but never reaches PlayerLoggedInEvent (e.g. client
        // closes the window mid-handshake).
        if (++lastConnectCheckSweepTick >= 600L) {
            lastConnectCheckSweepTick = 0L;
            if (!connectCheckCache.isEmpty()) {
                long cutoff = System.currentTimeMillis() - CONNECT_CHECK_TTL_MS;
                connectCheckCache.entrySet().removeIf(e -> e.getValue().insertedAt() < cutoff);
            }
        }

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
            // AUDIT FIX: also skip players who disconnected while queued — their entity
            // still carries the player_synced tag, so the old guard chain snapshotted a
            // stale entity and burned a DB round-trip before the online=0 check caught it.
            if (!player.hasDisconnected() && !player.isDeadOrDying() && !syncNotCompletedPlayer.contains(puuid)
                    && !pendingLogoutSaves.containsKey(puuid) && player.getTags().contains("player_synced")) {
                ReentrantLock lock = getPlayerLock(puuid);
                if (lock.tryLock()) {
                    try {
                        // PHASE 18: freeze on main thread (fast copies), materialize on BG.
                        final DeferredPlayerSnapshot frozen = snapshotPlayerData(player);
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
                                // PHASE 18: heavy serialization on BG.
                                PlayerDataSnapshot snapshot = frozen.materialize();
                                // PHASE 7 PERF: hash-skip identical snapshots.
                                int newHash = computeSnapshotHash(snapshot);
                                Integer prev = lastWrittenSnapshotHash.get(puuid);
                                if (prev != null && prev == newHash) {
                                    // AUDIT FIX (dup/perf): the core hash does NOT cover
                                    // backpack/SS blobs (SavedData keyed by item UUID).
                                    // A player moving items INSIDE a backpack leaves the
                                    // core hash unchanged — previously the early return
                                    // dropped the changed blob until logout, widening the
                                    // crash-loss window. Persist backpack-only changes;
                                    // the per-UUID hash inside skipUnchanged=true keeps
                                    // unchanged blobs from being rewritten.
                                    ModsSupport.saveBackpackSnapshots(backpackSnapshots, true);
                                    return;
                                }
                                boolean persisted = writeSnapshotToDB(snapshot);
                                if (persisted) {
                                    lastWrittenSnapshotHash.put(puuid, newHash);
                                    // AUDIT FIX (write amplification): skipUnchanged=true —
                                    // health/xp churn changes the core hash on nearly every
                                    // auto-save, but the backpack MEDIUMBLOBs are usually
                                    // untouched; don't rewrite them unconditionally.
                                    ModsSupport.saveBackpackSnapshots(backpackSnapshots, true);
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

    // FIX COMPAT: priority=LOW so we run after revive mods (Revive Me / HardcoreRevival
    // / CorailTombstone) that cancel LivingDeathEvent at NORMAL/HIGH priority. NeoForge
    // bus 8.x skips canceled events at the @SubscribeEvent dispatcher level, so this
    // handler runs ONLY for un-canceled (real, finalized) deaths — exactly when the
    // death-save (non-item progression) should fire.
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOW)
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        String puuid = player.getUUID().toString();

        if (deadPlayerWhileLogging.contains(puuid)) return;

        // Always cache curios on death (API returns empty for dead players later)
        CuriosCache.tryStoreCuriosToCache(player);

        // PHASE 19: honour save_on_death config. Keeping-charm / death-drop-replacement
        // mods (Twilight Forest Charm of Keeping, Corail Tombstone items, etc.) run
        // their own event handlers during LivingDeathEvent. When their priority is
        // higher than ours (LOW), they've already moved items out of the drops list
        // — our snapshot at this point captures the post-keep inventory, which is
        // usually the desired behaviour.
        // If admins diagnose a keeping-charm interaction, setting save_on_death=false
        // disables this snapshot entirely; the normal onPlayerLogout save still fires
        // on disconnect and captures the post-respawn state.
        if (!JdbcConfig.SAVE_ON_DEATH.get()) return;

        // Immediately save ALL player data on death (snapshot + async).
        // LivingDeathEvent fires BEFORE vanilla items are dropped, so the snapshot
        // captures whatever keeping-charms have already reserved + the rest.
        // This protects against: server crash after death, network disconnect before
        // onPlayerLogout fires, or any scenario where the logout handler is skipped.
        // The normal logout save will overwrite this with the final post-death state.
        if (!player.getTags().contains("player_synced")) return;
        if (syncNotCompletedPlayer.contains(puuid)) return;
        if (pendingLogoutSaves.containsKey(puuid)) return; // logout save already in flight

        ReentrantLock lock = getPlayerLock(puuid);
        if (!lock.tryLock()) return; // Skip if another save is in progress
        try {
            // FIX DUP-DEATH: death-save writes ONLY non-item progression fields, so we
            // skip the backpack / SS / RS2 main-thread snapshots that the logout-save
            // would normally produce. Saves CPU on the hot death path too.
            final DeferredPlayerSnapshot frozen = snapshotPlayerData(player);

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
                    long t0 = System.currentTimeMillis();
                    // PHASE 18: materialize the frozen snapshot on BG.
                    PlayerDataSnapshot snapshot = frozen.materialize();
                    // FIX DUP-DEATH: write ONLY non-item progression fields. Items are
                    // owned by the logout-save (post-drop / post-corpse). Writing the
                    // pre-death inventory here would dup with the corpse mod's items.
                    // Unused snapshot fields (backpackSnapshots, ssSnapshots, rs2DiskUuids,
                    // rs2Level, rs2Registry) are intentionally not persisted by the
                    // death-save: they are item-bearing and belong to the logout-save.
                    boolean persisted = writeNonItemSnapshotToDB(snapshot);
                    if (persisted) {
                        long dur = System.currentTimeMillis() - t0;
                        PlayerSync.LOGGER.info("Death-save (non-item) completed for player {} in {}ms", puuid, dur);
                        SyncLogger.saveCompleted(puuid, "DEATH", dur);
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
