package vip.fubuki.playersync.sync;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.WorldData;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import vip.fubuki.playersync.PlayerSync;
import vip.fubuki.playersync.config.JdbcConfig;
import vip.fubuki.playersync.util.JDBCsetUp;
import vip.fubuki.playersync.util.LocalJsonUtil;
import vip.fubuki.playersync.util.PSThreadPoolFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Mod.EventBusSubscriber
public class VanillaSync {

    public static void register() {}

    static ExecutorService executorService = Executors.newCachedThreadPool(new PSThreadPoolFactory("PlayerSync"));

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

        JDBCsetUp.QueryResult advancementsQuery = JDBCsetUp
                .executeQuery("SELECT advancements FROM player_data WHERE uuid='" + player_uuid + "'");
        ResultSet advancementsResultSet = advancementsQuery.resultSet();

        if (!advancementsResultSet.next()) {
            PlayerSync.LOGGER.debug("No advancements found for player " + player_uuid);
            advancementsResultSet.close();
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
            advancementsResultSet.close();

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
            advancementsResultSet.close();
        }
    }

    public static void doPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) throws SQLException, CommandSyntaxException, IOException {
        String player_uuid = event.getEntity().getUUID().toString();
        PlayerSync.LOGGER.info("Starting synchronization for player " + player_uuid);

        // First query: check basic player data
        JDBCsetUp.QueryResult qr1 = JDBCsetUp.executeQuery("SELECT online, last_server FROM player_data WHERE uuid='" + player_uuid + "'");
        ResultSet rs1 = qr1.resultSet();
        ServerPlayer serverPlayer = (ServerPlayer) event.getEntity();
        if (!rs1.next()){
            store(event.getEntity(), true);
            return;
        }
        boolean online = rs1.getBoolean("online");
        int lastServer = rs1.getInt("last_server");

        // Second query: retrieve full player data
        JDBCsetUp.QueryResult qr2 = JDBCsetUp.executeQuery("SELECT * FROM player_data WHERE uuid='" + player_uuid + "'");
        ResultSet rs2 = qr2.resultSet();

        // Check if player is already online on another server
        if (online && lastServer != JdbcConfig.SERVER_ID.get()) {
            JDBCsetUp.QueryResult qr3 = JDBCsetUp.executeQuery("SELECT last_update,enable FROM server_info WHERE id='" + lastServer + "'");
            ResultSet rs3 = qr3.resultSet();
            if (rs3.next()){
                long last_update = rs3.getLong("last_update");
                boolean enable = rs3.getBoolean("enable");
                if (enable && System.currentTimeMillis() < last_update + 300000.0){
                    event.getEntity().removeTag("player_synced");
                    serverPlayer.connection.disconnect(Component.translatable("playersync.already_online"));
                    return;
                }
                JDBCsetUp.executeUpdate("UPDATE server_info SET enable= '0' WHERE id=" + lastServer);
            }
            rs3.close();
        }
        JDBCsetUp.executeUpdate("UPDATE server_info SET last_update=" + System.currentTimeMillis() + " WHERE id=" + JdbcConfig.SERVER_ID.get());
        JDBCsetUp.executeUpdate("UPDATE player_data SET online= '1',last_server=" + JdbcConfig.SERVER_ID.get() + " WHERE uuid='" + player_uuid + "'");

        if (rs2.next()) {
            // Restore basic attributes
            serverPlayer.setHealth(rs2.getInt("health"));
            serverPlayer.getFoodData().setFoodLevel(rs2.getInt("food_level"));
            serverPlayer.totalExperience = 0;
            serverPlayer.experienceLevel = 0;
            serverPlayer.experienceProgress = 0;
            serverPlayer.giveExperiencePoints(rs2.getInt("xp"));
            serverPlayer.setScore(rs2.getInt("score"));

            // Restore left-hand item
            String leftHandEncoded = rs2.getString("left_hand");
            String leftHandNBT = deserializeString(leftHandEncoded);
            serverPlayer.setItemInHand(InteractionHand.OFF_HAND,
                    ItemStack.of(NbtUtils.snbtToStructure(leftHandNBT)));

            // Restore cursor item
            String cursorsEncoded = rs2.getString("cursors");
            String cursorsNBT = deserializeString(cursorsEncoded);
            serverPlayer.containerMenu.setCarried(
                    ItemStack.of(NbtUtils.snbtToStructure(cursorsNBT))
            );

            // Restore armor
            String armor_data = rs2.getString("armor");
            if (armor_data.length() > 2) {
                Map<Integer, String> equipment = LocalJsonUtil.StringToEntryMap(armor_data);
                for (Map.Entry<Integer, String> entry : equipment.entrySet()) {
                    serverPlayer.getInventory().armor.set(entry.getKey(), deserialize(entry));
                }
            }

            // Restore inventory
            Map<Integer, String> inventory = LocalJsonUtil.StringToEntryMap(rs2.getString("inventory"));
            for (Map.Entry<Integer, String> entry : inventory.entrySet()) {
                serverPlayer.getInventory().setItem(entry.getKey(), deserialize(entry));
            }

            // Restore Ender Chest
            Map<Integer, String> ender_chest = LocalJsonUtil.StringToEntryMap(rs2.getString("enderchest"));
            for (Map.Entry<Integer, String> entry : ender_chest.entrySet()) {
                serverPlayer.getEnderChestInventory().setItem(entry.getKey(), deserialize(entry));
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

        // Mod support
        ModsSupport modsSupport = new ModsSupport();
        modsSupport.onPlayerJoin(serverPlayer);
        serverPlayer.addTag("player_synced");

        rs2.close();
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

    public static ItemStack deserialize(Map.Entry<Integer, String> entry) throws CommandSyntaxException {
        String nbt = deserializeString(entry.getValue());
        CompoundTag compoundTag = NbtUtils.snbtToStructure(nbt);
        return ItemStack.of(compoundTag);
    }

    /**
     * Deserializes a string from the database back into an NBT string.
     * Handles both the new Base64 format (prefixed with "B64:") and the old custom format.
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
        JDBCsetUp.executeUpdate("UPDATE player_data SET online= '0' WHERE uuid='" + player_uuid + "'");
        store(event.getEntity(), false);
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) throws SQLException {
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

    public static void store(Player player, boolean init) throws SQLException, IOException {
        String player_uuid = player.getUUID().toString();
        PlayerSync.LOGGER.info("Storing data for player " + player_uuid + " (init=" + init + ")");

        // Basic Attributes
        int XP = player.totalExperience;
        int score = player.getScore();
        int food_level = player.getFoodData().getFoodLevel();
        int health = (int) player.getHealth();
        // Left Hand
        String left_hand = serialize(player.getItemInHand(InteractionHand.OFF_HAND).serializeNBT().toString());
        // Cursor
        String cursors = serialize(player.containerMenu.getCarried().serializeNBT().toString());
        // Equipment (Armor)
        Map<Integer, String> equipment = new HashMap<>();
        for (int i = 0; i < player.getInventory().armor.size(); i++) {
            ItemStack itemStack = player.getInventory().armor.get(i);
            equipment.put(i, serialize(itemStack.serializeNBT().toString()));
        }
        // Inventory
        Inventory inventory = player.getInventory();
        Map<Integer, String> inventoryMap = new HashMap<>();
        for (int i = 0; i < inventory.items.size(); i++) {
            CompoundTag itemNBT = inventory.items.get(i).serializeNBT();
            inventoryMap.put(i, serialize(itemNBT.toString()));
        }
        // Ender Chest
        Map<Integer, String> ender_chest = new HashMap<>();
        for (int i = 0; i < player.getEnderChestInventory().getContainerSize(); i++) {
            CompoundTag itemNBT = player.getEnderChestInventory().getItem(i).serializeNBT();
            ender_chest.put(i, serialize(itemNBT.toString()));
        }

        if(ModList.get().isLoaded("sophisticatedbackpacks")){
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
            if (server != null && server.isDedicatedServer() ) {
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
            JDBCsetUp.executeUpdate("INSERT INTO player_data (uuid,armor,inventory,enderchest,advancements,effects,xp,food_level,health,score,left_hand,cursors,online) VALUES ('" + player_uuid + "','" + equipment + "','" + inventoryMap + "','" + ender_chest + "','" + advancements + "','" + effectMap + "','" + XP + "','" + food_level + "','" + health + "','" + score + "','" + left_hand + "','" + cursors + "',online=true)");
        } else {
            JDBCsetUp.executeUpdate("UPDATE player_data SET inventory = '" + inventoryMap + "',armor='" + equipment + "' ,xp='" + XP + "',effects='" + effectMap + "',enderchest='" + ender_chest + "',score='" + score + "',food_level='" + food_level + "',health='" + health + "',advancements='" + json + "',left_hand='" + left_hand + "',cursors='" + cursors + "' WHERE uuid = '" + player_uuid + "'");
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
            long current = System.currentTimeMillis();
            JDBCsetUp.executeUpdate("UPDATE server_info SET last_update =" + current + " WHERE id= " + JdbcConfig.SERVER_ID.get());
        }
    }


    // New fields for auto-save
    private static int autoSaveTickCounter = 0;
    private static final int AUTO_SAVE_INTERVAL_TICKS = 1200; // Every Minute

    //AutoSave
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        // Run at the end phase to avoid interfering with game logic
        if (event.phase == TickEvent.Phase.END) {
            autoSaveTickCounter++;
            if (autoSaveTickCounter >= AUTO_SAVE_INTERVAL_TICKS) {
                autoSaveTickCounter = 0;
                // Retrieve the current server instance
                MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                if (server != null) {
                    // Iterate through all online players
                    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
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
        }
    }
}
