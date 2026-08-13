package vip.fubuki.playersync.sync;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.Dynamic;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.WorldData;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerNegotiationEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.server.ServerLifecycleHooks;
import vip.fubuki.playersync.PlayerSync;
import vip.fubuki.playersync.config.JdbcConfig;
import vip.fubuki.playersync.sync.addons.CuriosCache;
import vip.fubuki.playersync.sync.addons.ModsSupport;
import vip.fubuki.playersync.util.ExperienceMath;
import vip.fubuki.playersync.util.JDBCsetUp;
import vip.fubuki.playersync.util.LocalJsonUtil;
import vip.fubuki.playersync.util.PSThreadPoolFactory;
import vip.fubuki.playersync.util.ServerIdentity;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;

@Mod.EventBusSubscriber
public class VanillaSync {

    public static void register() {
    }

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
        PlayerSync.LOGGER.info("Player entity joining level " + player_uuid);

        // try-with-resources so the connection and statement are released on every exit path:
        // the advancements restore below returns early in four places and writes files, and
        // closing only the ResultSet leaves the connection behind on all of them.
        try (JDBCsetUp.QueryResult advancementsQuery = JDBCsetUp
                .executeQuery("SELECT advancements FROM player_data WHERE uuid='" + player_uuid + "'")) {
            ResultSet advancementsResultSet = advancementsQuery.resultSet();

            if (!advancementsResultSet.next()) {
                PlayerSync.LOGGER.debug("No advancements found for player " + player_uuid);
                return;
            }

            // Restore Advancements
            File gameDir = Objects.requireNonNull(serverPlayer.getServer()).getServerDirectory();

            final MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server != null && server.isDedicatedServer()) {
                PlayerSync.LOGGER.debug("Attempting to write dedicated server advancement file");
                File advancements = new File(gameDir,
                        getSyncWorldForServer() + "/advancements" + "/" + player_uuid + ".json");
                byte[] bytes = advancementsResultSet.getString("advancements").getBytes();

                // only create advancements file if at least "{}" has been stored in the field
                if (bytes.length < 2) {
                    PlayerSync.LOGGER.debug("Skip writing advancements for player " + player_uuid);
                    return;
                }

                File advancementsDir = advancements.getParentFile();
                if (advancementsDir != null && !advancementsDir.exists()) {
                    PlayerSync.LOGGER.info("Creating advancements directory " + advancementsDir.getPath());
                    boolean createdDir = advancementsDir.mkdirs();
                    if (!createdDir) {
                        PlayerSync.LOGGER.error("Aborting advancements sync. Failed to create advancements "
                                + "directory at " + advancementsDir.getPath());
                        return;
                    }
                }

                if (!advancements.exists()) {
                    try {
                        PlayerSync.LOGGER.info("Creating new advancement file for player " + player_uuid);
                        advancements.createNewFile();
                    } catch (IOException e) {
                        PlayerSync.LOGGER.error("Aborting advancements sync. Failed to create advancements file at "
                                + advancements.getAbsolutePath(), e);
                        return;
                    }
                }
                PlayerSync.LOGGER.debug("Writing advancement file " + advancements.toPath() + " for player " + player_uuid);
                PlayerSync.LOGGER.trace("Writing advancement file for player " + player_uuid + ": "
                        + new String(bytes, StandardCharsets.UTF_8));
                Files.write(advancements.toPath(), bytes);

                // reload the json files on the server after updating them
                PlayerAdvancements playeradvancements = serverPlayer.getAdvancements();
                playeradvancements.reload(server.getAdvancements());

            } else {
                PlayerSync.LOGGER.debug("Writing non-dedicated server advancement files");
                File[] files = scanAdvancementsFile(player_uuid, gameDir);
                for (File file : files) {
                    if (file == null)
                        continue;
                    byte[] bytes = advancementsResultSet.getString("advancements").getBytes();
                    Files.write(file.toPath(), bytes);
                }
            }
        }
    }

    public static void doPlayerConnect(PlayerNegotiationEvent event) {
        try {
            UUID profileId = event.getProfile().getId();
            if (profileId == null) {
                // On offline-mode servers (including servers behind offline-mode proxies)
                // the GameProfile carries no id during negotiation; the server assigns the
                // name-derived offline UUID later in the login flow. Use the same derivation
                // here, or the already-online check would NPE and kick every player.
                profileId = UUIDUtil.createOfflinePlayerUUID(event.getProfile().getName());
            }
            String player_uuid = profileId.toString();
            PlayerSync.LOGGER.info("Detected connection from player" + player_uuid + ",starting checking");
            boolean online;
            int lastServer;

            // First query: check basic player data and check whether player can join into server.
            // try-with-resources so the connection and statement are released even if a getter throws.
            try (JDBCsetUp.QueryResult qr1 = JDBCsetUp.executeQuery("SELECT online, last_server FROM player_data WHERE uuid='" + player_uuid + "'")) {
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
                // try-with-resources so the connection is released even if the enable=0 UPDATE below throws.
                try (JDBCsetUp.QueryResult qr2 = JDBCsetUp.executeQuery("SELECT last_update,enable FROM server_info WHERE id='" + lastServer + "'")) {
                    ResultSet rs2 = qr2.resultSet();
                    if (rs2.next()) {
                        long last_update = rs2.getLong("last_update");
                        boolean enable = rs2.getBoolean("enable");
                        if (enable && System.currentTimeMillis() < last_update + ServerIdentity.LIVENESS_WINDOW_MS) {
                            event.getConnection().disconnect(Component.translatableWithFallback("playersync.already_online","You can't join more than one synchronization server at the same time."));
                            return;
                        }
                        JDBCsetUp.executeUpdate("UPDATE server_info SET enable= '0' WHERE id=" + lastServer);
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

    public static void doPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        ServerPlayer joinedPlayer = (ServerPlayer) event.getEntity();
        String player_uuid = joinedPlayer.getUUID().toString();
        if (joinedPlayer.isDeadOrDying()) {
            deadPlayerWhileLogging.add(player_uuid);
            joinedPlayer.removeTag("player_synced");

            // Simulate normal death behavior
            MinecraftServer server = joinedPlayer.getServer();
            if (server != null) {
                ResourceKey<Level> respawnLevel = joinedPlayer.getRespawnDimension();
                BlockPos respawnPos = joinedPlayer.getRespawnPosition();
                double respawnX;
                double respawnY;
                double respawnZ;
                if (respawnPos != null && respawnLevel != null) {
                    ServerLevel level = server.getLevel(respawnLevel);
                    respawnX = respawnPos.getX();
                    respawnY = respawnPos.getY();
                    respawnZ = respawnPos.getZ();
                    if (level != null) {
                        joinedPlayer.teleportTo(level, respawnX, respawnY + 1, respawnZ, 0, 0);
                    }
                } else {
                    PlayerSync.LOGGER.debug("Player " + player_uuid + " has no respawn point");
                }
            } else {
                PlayerSync.LOGGER.warn("Trying to get server,but got a null");
            }

            joinedPlayer.setHealth(1);
            try {
                JDBCsetUp.executeUpdate("UPDATE server_info SET last_update=" + System.currentTimeMillis() + " WHERE id=" + JdbcConfig.SERVER_ID.get());
                JDBCsetUp.executeUpdate("UPDATE player_data SET online= '1',last_server=" + JdbcConfig.SERVER_ID.get() + " WHERE uuid='" + player_uuid + "'");
            } catch (SQLException e) {
                PlayerSync.LOGGER.error("An error occurred while trying to execute a dead or dying player" + e.getMessage());
            }
            joinedPlayer.connection.disconnect(Component.translatableWithFallback("playersync.wrong_entity_status","An error occurred while creating playerEntity in the world,please login again."));
            return;
        }

        try {
            PlayerSync.LOGGER.info("Starting synchronization for player " + player_uuid);

            // First query: check basic player data
            syncNotCompletedPlayer.add(player_uuid);
            // try-with-resources so both queries release their connection and statement on
            // every exit path: anything thrown by the restore below unwinds to the catch, which
            // only repairs the player's state and would leave the handles open.
            try (JDBCsetUp.QueryResult qr1 = JDBCsetUp.executeQuery("SELECT online, last_server FROM player_data WHERE uuid='" + player_uuid + "'")) {
                ResultSet rs1 = qr1.resultSet();
                ServerPlayer serverPlayer = (ServerPlayer) event.getEntity();

                // Mod support
                ModsSupport modsSupport = new ModsSupport();
                modsSupport.doCuriosRestore(serverPlayer);

                if (!rs1.next()) {
                    store(event.getEntity(), true);
                    JDBCsetUp.executeUpdate("UPDATE server_info SET last_update=" + System.currentTimeMillis() + " WHERE id=" + JdbcConfig.SERVER_ID.get());
                    JDBCsetUp.executeUpdate("UPDATE player_data SET online= '1',last_server=" + JdbcConfig.SERVER_ID.get() + " WHERE uuid='" + player_uuid + "'");
                    if (revertIfDisconnectedDuringSync(serverPlayer, player_uuid)) {
                        return;
                    }
                    PlayerSync.LOGGER.info("New player detected,init completed.");
                    syncNotCompletedPlayer.remove(player_uuid);
                    return;
                }

                // Second query: retrieve full player data
                try (JDBCsetUp.QueryResult qr2 = JDBCsetUp.executeQuery("SELECT * FROM player_data WHERE uuid='" + player_uuid + "'")) {
                    ResultSet rs2 = qr2.resultSet();

                    JDBCsetUp.executeUpdate("UPDATE server_info SET last_update=" + System.currentTimeMillis() + " WHERE id=" + JdbcConfig.SERVER_ID.get());
                    JDBCsetUp.executeUpdate("UPDATE player_data SET online= '1',last_server=" + JdbcConfig.SERVER_ID.get() + " WHERE uuid='" + player_uuid + "'");

                    if (rs2.next()) {
                        // Restore basic attributes
                        int health = rs2.getInt("health");
                        if (health <= 0) {
                            serverPlayer.setHealth(1);
                        } else {
                            serverPlayer.setHealth(health);
                        }
                        serverPlayer.getFoodData().setFoodLevel(rs2.getInt("food_level"));

                        setXpForPlayer(serverPlayer, rs2.getInt("xp"));
                        serverPlayer.setScore(rs2.getInt("score"));

                        // Restore left-hand item
                        String leftHandEncoded = rs2.getString("left_hand");
                        serverPlayer.setItemInHand(InteractionHand.OFF_HAND,
                                deserializeAndCreatePlaceholderIfNeeded(leftHandEncoded));

                        // Restore cursor item
                        String cursorsEncoded = rs2.getString("cursors");
                        serverPlayer.containerMenu.setCarried(
                                deserializeAndCreatePlaceholderIfNeeded(cursorsEncoded));

                        // Restore armor
                        String armor_data = rs2.getString("armor");
                        if (armor_data.length() > 2) {
                            Map<Integer, String> equipment = LocalJsonUtil.StringToEntryMap(armor_data);
                            for (Map.Entry<Integer, String> entry : equipment.entrySet()) {
                                serverPlayer.getInventory().armor.set(entry.getKey(), deserializeAndCreatePlaceholderIfNeeded(entry.getValue()));
                            }
                        }

                        // Restore inventory
                        Map<Integer, String> inventory = LocalJsonUtil.StringToEntryMap(rs2.getString("inventory"));
                        for (Map.Entry<Integer, String> entry : inventory.entrySet()) {
                            serverPlayer.getInventory().setItem(entry.getKey(), deserializeAndCreatePlaceholderIfNeeded(entry.getValue()));
                        }

                        // Restore Ender Chest
                        Map<Integer, String> ender_chest = LocalJsonUtil.StringToEntryMap(rs2.getString("enderchest"));
                        for (Map.Entry<Integer, String> entry : ender_chest.entrySet()) {
                            serverPlayer.getEnderChestInventory().setItem(entry.getKey(), deserializeAndCreatePlaceholderIfNeeded(entry.getValue()));
                        }

                        // Restore Effects
                        String effectData = rs2.getString("effects");
                        if (effectData.length() > 2) {
                            serverPlayer.removeAllEffects();
                            Map<Integer, String> effects = LocalJsonUtil.StringToEntryMap(effectData);
                            for (Map.Entry<Integer, String> entry : effects.entrySet()) {
                                CompoundTag effectTag = NbtUtils.snbtToStructure(deserializeString(entry.getValue()));
                                MobEffectInstance mobEffectInstance = MobEffectInstance.load(effectTag);
                                if (mobEffectInstance != null) {
                                    serverPlayer.addEffect(mobEffectInstance);
                                }
                            }
                        }
                    }
                }

                modsSupport.doBackPackRestore(serverPlayer);

                if (revertIfDisconnectedDuringSync(serverPlayer, player_uuid)) {
                    return;
                }
                serverPlayer.addTag("player_synced");
                PlayerSync.LOGGER.info("Sync data for player {} completed.", player_uuid);
                syncNotCompletedPlayer.remove(player_uuid);
            }
        } catch (Exception e) {
            PlayerSync.LOGGER.error("Internal Exception detected!", e);
            if (joinedPlayer.hasDisconnected()) {
                // The disconnect raced this task: if the logout handler already wrote
                // online=0 before our online=1 UPDATE landed, revert it so a failed sync
                // does not leave the player ghost-online. The player is gone, so it is safe
                // to drop the not-synced marker.
                try {
                    markOffline(player_uuid);
                    PlayerSync.LOGGER.warn("Player {} disconnected during failed sync,reverted online status", player_uuid);
                } catch (SQLException revertException) {
                    PlayerSync.LOGGER.error("Failed to revert online status for player {}", player_uuid, revertException);
                }
                syncNotCompletedPlayer.remove(player_uuid);
            } else {
                // Sync failed while the player is still connected. Keep the not-synced
                // marker so logout and autosave refuse to persist the partially-restored
                // inventory over the player's good saved data.
                PlayerSync.LOGGER.warn("Sync failed for connected player {}; keeping not-synced marker so the partial state is not saved", player_uuid);
            }
        }
    }

    // Clears online, but only while this server still owns the session: these writes queue on a
    // bounded executor, so by the time one runs the player may be online on another server.
    private static void markOffline(String player_uuid) throws SQLException {
        JDBCsetUp.executeUpdate("UPDATE player_data SET online= '0' WHERE uuid='" + player_uuid + "' AND last_server=" + JdbcConfig.SERVER_ID.get());
    }

    // The player may disconnect while the sync task is still running. In that case the
    // logout handler has already seen the not-yet-synced marker, refused to save and
    // written online=0 — but our own online=1 UPDATE may have landed after it, leaving
    // the player marked online on this server with no one connected (and kicked from every
    // server by the already-online check until this server's heartbeat goes stale). Revert it.
    // Returns true if the player disconnected and the sync result must be discarded.
    private static boolean revertIfDisconnectedDuringSync(ServerPlayer player, String player_uuid) throws SQLException {
        if (!player.hasDisconnected()) {
            return false;
        }
        markOffline(player_uuid);
        // The logout handler normally removes the marker itself. This removal only matters
        // when the logout event has not fired yet.
        syncNotCompletedPlayer.remove(player_uuid);
        PlayerSync.LOGGER.warn("Player {} disconnected during sync,reverted online status", player_uuid);
        return true;
    }

    @SubscribeEvent
    public static void onPlayerConnect(PlayerNegotiationEvent event) {
        executorService.submit(() -> {
            try {
                doPlayerConnect(event);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        // player_synced is persisted in the player NBT, so a returning player arrives still
        // carrying it. Clear it so doPlayerSaveToFile cannot store pre-sync state as if synced.
        event.getEntity().removeTag("player_synced");
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

        String nbtString = deserializeString(serializedNbt);
        CompoundTag compoundTag = snbtToFixedCompoundTag(nbtString);

        if (compoundTag == null || compoundTag.isEmpty() || !compoundTag.contains("id", Tag.TAG_STRING)) {
            return ItemStack.EMPTY; // Invalid or empty tag
        }

        ResourceLocation registryName = ResourceLocation.tryParse(compoundTag.getString("id"));

        if (registryName == null) {
            PlayerSync.LOGGER.warn("Failed to parse registry name from NBT: {}", nbtString);
            return ItemStack.EMPTY; // Cannot determine item type
        }

        if (ForgeRegistries.ITEMS.containsKey(registryName)) {
            // Item exists (could be vanilla or a loaded mod item), restore normally
            try {
                ItemStack restoredItem = ItemStack.of(compoundTag);
                // Only return the restored item if the ItemStack.of did not unexpectedly
                // returned an empty item
                // Either the item is not empty, or it is empty and the original tag was also
                // empty or it was an empty inventory slot
                if (!restoredItem.isEmpty() || compoundTag.isEmpty()
                        || registryName.equals(ResourceLocation.parse("air"))) {
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

        CompoundTag placeholderNbt = placeholder.getOrCreateTag();
        // Store the original serialized NBT string, not the parsed CompoundTag string
        placeholderNbt.putString("playersync:original_item_nbt", serializedNbt);
        placeholderNbt.putString("playersync:original_item_id", registryName.toString());

        // Add a unique UUID to ensure the item is unstackable
        // Stacked placerholders would be converted into a single item when restoring item
        placeholderNbt.putUUID("playersync:unique_id", UUID.randomUUID());

        // Add display name and lore
        CompoundTag displayTag = placeholderNbt.getCompound("display");
        if (!placeholderNbt.contains("display"))
            placeholderNbt.put("display", displayTag);

        String placeholderItemTitleOverride = JdbcConfig.ITEM_PLACEHOLDER_TITLE_OVERRIDE.get();
        displayTag.putString("Name", Component.Serializer.toJson(
                Component
                        .literal(placeholderItemTitleOverride != null && !placeholderItemTitleOverride.isBlank()
                                ? placeholderItemTitleOverride
                                : Component.translatable("playersync.item_placeholder_title").getString())
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.RED).withItalic(true))));

        ListTag loreList = new ListTag();
        String placeholderItemDetails = registryName.toString();

        // add a stack size if it is available
        PlayerSync.LOGGER.warn("Item {}: {}", registryName, compoundTag);
        int placeholderItemAmount = compoundTag.getInt("Count");
        if (placeholderItemAmount > 1) {
            placeholderItemDetails = placeholderItemAmount + "x " + placeholderItemDetails;
        }

        loreList.add(StringTag.valueOf(Component.Serializer.toJson(
                Component.literal(placeholderItemDetails)
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY).withItalic(false)))));
        // add newline
        loreList.add(StringTag.valueOf(Component.Serializer.toJson(Component.literal(""))));

        String placeholderItemDescriptionOverride = JdbcConfig.ITEM_PLACEHOLDER_DESCRIPTION_OVERRIDE.get();
        String placeholderItemDescriptionLines = placeholderItemDescriptionOverride != null && !placeholderItemDescriptionOverride.isBlank()
                ? placeholderItemDescriptionOverride
                : Component.translatable("playersync.item_placeholder_description").getString();

        for (String descriptionLine : placeholderItemDescriptionLines.split("\n")) {
            loreList.add(StringTag.valueOf(Component.Serializer.toJson(
                    Component.literal(descriptionLine)
                            .setStyle(Style.EMPTY.withColor(ChatFormatting.DARK_GRAY)))));
        }
        displayTag.put("Lore", loreList);

        return placeholder;
    }

    public static CompoundTag snbtToFixedCompoundTag(String nbtString) throws CommandSyntaxException {
        CompoundTag parsedTag = TagParser.parseTag(nbtString);

        int currentDataVersion = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
        int snbtDataVersion = NbtUtils.getDataVersion(parsedTag, 500);

        Dynamic<Tag> dynamicTagInput = new Dynamic<>(NbtOps.INSTANCE, parsedTag);

        Dynamic<Tag> updatedDynamicTag = DataFixers.getDataFixer().update(
                References.ITEM_STACK,
                dynamicTagInput,
                snbtDataVersion,
                currentDataVersion);
        return (CompoundTag) updatedDynamicTag.getValue();
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
                PlayerSync.LOGGER.error("Base64 decoding failed for data: " + encoded, ex);
                // fallback to legacy decoding below
            }
        }
        // Legacy fallback using custom replacement
        return encoded.replace("|", ",")
                .replace("^", "\"")
                .replace("<", "{")
                .replace(">", "}")
                .replace("~", "'");
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
        return serialize(object, JdbcConfig.USE_LEGACY_SERIALIZATION.get());
    }

    static String serialize(String object, boolean useLegacySerialization) {
        if (useLegacySerialization) {
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
        JDBCsetUp.executeUpdate("UPDATE server_info SET last_update=" + System.currentTimeMillis() + " WHERE id=" + JdbcConfig.SERVER_ID.get());
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
    public static void onServerShutdown(ServerStoppedEvent event) throws SQLException {
        JDBCsetUp.executeUpdate("UPDATE server_info SET enable= '0' WHERE id=" + JdbcConfig.SERVER_ID.get());
    }

    public static void doPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) throws SQLException, IOException {
        String player_uuid = event.getEntity().getUUID().toString();
        markOffline(player_uuid);
        store(event.getEntity(), false);
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) throws SQLException {
        String player_uuid = event.getEntity().getUUID().toString();
        if (deadPlayerWhileLogging.contains(player_uuid)) {
            PlayerSync.LOGGER.warn("A dead or dying player was kicked,which uuid is:{}", player_uuid);
            markOffline(player_uuid);
            deadPlayerWhileLogging.remove(player_uuid);
        } else if (syncNotCompletedPlayer.contains(player_uuid)) {
            PlayerSync.LOGGER.warn("A player logged out with uncompleted sync data,which uuid is:{}.For the safety,the new data won't be saved", player_uuid);
            // A no-op if our claim never landed: the row was never ours to clear.
            markOffline(player_uuid);
            syncNotCompletedPlayer.remove(player_uuid);
        } else {
            // Mod support
            ModsSupport modsSupport = new ModsSupport();
            modsSupport.onPlayerLeave(event.getEntity());
            executorService.submit(() -> {
                try {
                    doPlayerLogout(event);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }
    }

    // Helper function to get the NBT string to be saved
    // If item is a placeholder, get original NBT; otherwise, get current NBT
    private static String getNbtForStorage(ItemStack itemStack) {
        if (itemStack.is(Items.PAPER) && itemStack.hasTag() && itemStack.getTag().contains("playersync:original_item_nbt", Tag.TAG_STRING)) {
            // It's our placeholder, retrieve the original NBT string
            return itemStack.getTag().getString("playersync:original_item_nbt");
        } else {
            // It's a normal item or empty, serialize its current NBT
            return serialize(serializeNBT(itemStack).toString());
        }
    }

    public static CompoundTag serializeNBT(ItemStack itemStack) {
        if (itemStack == null || itemStack.isEmpty()) {
            return new CompoundTag();
        }
        // Serialize the ItemStack to NBT
        CompoundTag compoundTag = new CompoundTag();
        itemStack.save(compoundTag);
        // Adding data version to allow newer version of Minecraft to properly update the itemstack from the db
        NbtUtils.addCurrentDataVersion(compoundTag);
        return compoundTag;
    }

    public static void store(Player player, boolean init) throws SQLException, IOException {
        String player_uuid = player.getUUID().toString();
        PlayerSync.LOGGER.info("Storing data for player " + player_uuid + " (init=" + init + ")");

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

        if (ModList.get().isLoaded("sophisticatedbackpacks")) {
            ModsSupport.storeSophisticatedBackpacks(player);
        }

        // Effects
        Map<MobEffect, MobEffectInstance> effects = player.getActiveEffectsMap();
        Map<Integer, String> effectMap = new HashMap<>();
        for (Map.Entry<MobEffect, MobEffectInstance> entry : effects.entrySet()) {
            CompoundTag effectTag = entry.getValue().save(new CompoundTag());
            effectMap.put(MobEffect.getId(entry.getKey()), serialize(effectTag.toString()));
        }

        // Advancements
        File advancements = null;
        byte[] advancementBytes = new byte[0];
        if (JdbcConfig.SYNC_ADVANCEMENTS.get()) {
            File gameDir = Objects.requireNonNull(player.getServer()).getServerDirectory();
            final MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server != null && server.isDedicatedServer()) {
                PlayerSync.LOGGER.trace("Reading dedicated server advancements");
                advancements = new File(gameDir, getSyncWorldForServer() + "/advancements" + "/" + player_uuid + ".json");
            } else {
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
            if (!advancements.exists()) {
                PlayerSync.LOGGER.warn("Advancements file for " + player_uuid + " does not exist (yet).");
            }

            if (advancements != null && advancements.exists()) {
                PlayerSync.LOGGER.debug("Storing advancements for " + player_uuid + " from " + advancements.toPath());
                advancementBytes = Files.readAllBytes(advancements.toPath());
            } else {
                PlayerSync.LOGGER.error("Unable to save advancements for player " + player_uuid);
            }
        }
        String json = new String(advancementBytes, StandardCharsets.UTF_8);
        PlayerSync.LOGGER.trace("Storing advancements for player " + player_uuid + ": " + json);

        // SQL Operation for player data
        if (init) {
            JDBCsetUp.executeUpdate("INSERT INTO player_data (uuid,armor,inventory,enderchest,advancements,effects,xp,food_level,health,score,left_hand,cursors,online) VALUES ('" + player_uuid + "','" + equipment + "','" + inventoryMap + "','" + ender_chest + "','" + json + "','" + effectMap + "','" + XP + "','" + food_level + "','" + health + "','" + score + "','" + left_hand + "','" + cursors + "',online=true)");
        } else {
            // Only write while this server still owns the session: a queued logout save can land
            // after the player has joined elsewhere, rolling that session back to our stale copy.
            int updated = JDBCsetUp.executeUpdateCount("UPDATE player_data SET inventory = '" + inventoryMap + "',armor='" + equipment + "' ,xp='" + XP + "',effects='" + effectMap + "',enderchest='" + ender_chest + "',score='" + score + "',food_level='" + food_level + "',health='" + health + "',advancements='" + json + "',left_hand='" + left_hand + "',cursors='" + cursors + "' WHERE uuid = '" + player_uuid + "' AND last_server = " + JdbcConfig.SERVER_ID.get());
            if (updated == 0) {
                // The one place that knows the data went nowhere; silence here is undebuggable.
                PlayerSync.LOGGER.info("Dropping save for player {}: the session is owned by another server", player_uuid);
            }
        }
    }

    private static String getSyncWorldForServer() {
        if (!JdbcConfig.SYNC_WORLD.get().isEmpty()) {
            PlayerSync.LOGGER.warn("Using configuration 'sync_world' on servers is deprecated. Please leave the array empty. Falling back to first entry.");
            return JdbcConfig.SYNC_WORLD.get().get(0);
        }

        final MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            PlayerSync.LOGGER.error("Unable to get current server. Assuming default level-name 'world'.");
            return "world";
        }

        final WorldData worldData = server.getWorldData();
        final String levelName = worldData.getLevelName();
        PlayerSync.LOGGER.debug("Using server level-name: " + levelName);

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

    static int tick = 0;

    @SubscribeEvent
    public static void onUpdate(TickEvent.LevelTickEvent event) throws SQLException {
        tick++;
        if (tick == 1800) {
            tick = 0;
            ServerIdentity.heartbeat();
        }
    }


    // New fields for auto-save
    private static int autoSaveTickCounter = 0;
    private static final int AUTO_SAVE_INTERVAL_TICKS = 1200; // Every Minute
    private static int autoCleanCuriosCacheTickCounter = 0;
    private static final int AUTO_CLEAN_CURIOS_CACHE_INTERVAL_TICKS = 36000; // Every 30 min

    //AutoSave
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        // Run at the end phase to avoid interfering with game logic
        if (event.phase == TickEvent.Phase.END) {
            autoSaveTickCounter++;
            autoCleanCuriosCacheTickCounter++;
            if (autoSaveTickCounter >= AUTO_SAVE_INTERVAL_TICKS) {
                autoSaveTickCounter = 0;
                // Retrieve the current server instance
                MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                if (server != null) {
                    // Iterate through all online players
                    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                        // Skip players whose sync has not completed: their in-memory state is
                        // not the synced data and autosaving it would overwrite good DB data
                        // (e.g. after a sync that threw while the player stayed connected).
                        if (syncNotCompletedPlayer.contains(player.getUUID().toString())) {
                            continue;
                        }
                        executorService.submit(() -> {
                            try {
                                // Call the same store method used in logout and file save events.
                                store(player, false);
                            } catch (Exception e) {
                                PlayerSync.LOGGER.error("Error auto-saving player " + player.getUUID(), e);
                            }
                        });
                        executorService.submit(() -> {
                            try {
                                new ModsSupport().StoreCurios(player, false);
                            } catch (SQLException e) {
                                PlayerSync.LOGGER.error("Error auto-saving Curios data for player " + player.getUUID(), e);
                            }
                        });
                    }
                }
            }
            if (autoCleanCuriosCacheTickCounter >= AUTO_CLEAN_CURIOS_CACHE_INTERVAL_TICKS) {
                autoCleanCuriosCacheTickCounter = 0;
                executorService.submit(() -> {
                    try {
                        CuriosCache.RemoveExpiredCuriosCache();
                    } catch (Exception e) {
                        PlayerSync.LOGGER.error("An error occurred while cleaning curios cache:" + e.getMessage());
                    }
                });
            }
        }
    }

    private static void setXpForPlayer(ServerPlayer serverPlayer, int databaseXp) {
        // Don't use giveExperience() as it has several side-effects:
        // triggers an event, sends network packets, increases the score, ...
        serverPlayer.totalExperience = databaseXp;

        // The per-level cost depends on the player's current level, so the level field is
        // walked along while the pure math distributes the total.
        ExperienceMath.LevelAndProgress levelAndProgress = ExperienceMath.levelAndProgressFromTotalXp(databaseXp, level -> {
            serverPlayer.experienceLevel = level;
            return serverPlayer.getXpNeededForNextLevel();
        });

        serverPlayer.experienceLevel = levelAndProgress.level();
        serverPlayer.experienceProgress = levelAndProgress.progress();

        PlayerSync.LOGGER.debug("Giving player "
                + serverPlayer.experienceLevel + " levels and "
                + serverPlayer.experienceProgress * 100 + "% experience progress, calculated from "
                + serverPlayer.totalExperience + " XP.");
    }

    private static int getTotalExperience(final Player player) {
        int totalXp = ExperienceMath.totalExperience(
                player.experienceLevel, player.experienceProgress, player.getXpNeededForNextLevel());

        PlayerSync.LOGGER.debug("Experience calcuation for "
                + player.experienceLevel + " levels and "
                + player.experienceProgress * 100 + "% experience progress yields "
                + totalXp + " XP.");

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
