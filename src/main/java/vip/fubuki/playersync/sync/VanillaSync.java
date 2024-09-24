package vip.fubuki.playersync.sync;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Mod.EventBusSubscriber
public class VanillaSync {

    public static void register(){}

    static ExecutorService executorService = Executors.newCachedThreadPool(new PSThreadPoolFactory("PlayerSync"));

    public static void doPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) throws SQLException, CommandSyntaxException, IOException {
        String player_uuid = event.getEntity().getUUID().toString();
        JDBCsetUp.QueryResult queryResult=JDBCsetUp.executeQuery("SELECT online, last_server FROM player_data WHERE uuid='"+player_uuid+"'");
        ResultSet resultSet=queryResult.resultSet();
        ServerPlayer serverPlayer = (ServerPlayer) event.getEntity();
        if(!resultSet.next()){
            store(event.getEntity(),true,Dist.CLIENT.isDedicatedServer());
            return;
        }
        boolean online = resultSet.getBoolean("online");
        int lastServer = resultSet.getInt("last_server");
        queryResult=JDBCsetUp.executeQuery("SELECT * FROM player_data WHERE uuid='"+player_uuid+"'");
        resultSet= queryResult.resultSet();
        if(online && lastServer != JdbcConfig.SERVER_ID.get()) {

            queryResult=JDBCsetUp.executeQuery("SELECT last_update,enable FROM server_info WHERE id='"+lastServer+"'");
            ResultSet getServerInfo = queryResult.resultSet();
            if(getServerInfo.next()){
                long last_update = getServerInfo.getLong("last_update");
                boolean enable = getServerInfo.getBoolean("enable");
                if(enable && System.currentTimeMillis() < last_update + 300000.0){
                    event.getEntity().removeTag("player_synced");
                    serverPlayer.connection.disconnect(Component.translatable("playersync.already_online"));
                    return;
                }
                JDBCsetUp.executeUpdate("UPDATE server_info SET enable= '0' WHERE id=" + lastServer);
            }

            getServerInfo.close();


        }
        JDBCsetUp.executeUpdate("UPDATE server_info SET last_update=" + System.currentTimeMillis() + " WHERE id=" + JdbcConfig.SERVER_ID.get());
        JDBCsetUp.executeUpdate("UPDATE player_data SET online= '1',last_server=" + JdbcConfig.SERVER_ID.get() + " WHERE uuid='"+player_uuid+"'");
        if(resultSet.next()) {
            //Easy Part
            serverPlayer.setHealth(resultSet.getInt("health"));
            serverPlayer.getFoodData().setFoodLevel(resultSet.getInt("food_level"));
            serverPlayer.totalExperience=0;
            serverPlayer.experienceLevel=0;
            serverPlayer.experienceProgress=0;
            serverPlayer.giveExperiencePoints(resultSet.getInt("xp"));
            serverPlayer.setScore(resultSet.getInt("score"));
            //Equipment
            String armor_data=resultSet.getString("armor");
            if(armor_data.length()>2) {
                Map<Integer, String> equipment = LocalJsonUtil.StringToEntryMap(armor_data);
                for (Map.Entry<Integer, String> entry : equipment.entrySet()) {
                    serverPlayer.getInventory().armor.set(entry.getKey(), deserialize(entry));
                }
            }
            //Inventory
            Map<Integer,String> inventory = LocalJsonUtil.StringToEntryMap(resultSet.getString("inventory"));
            for (Map.Entry<Integer, String> entry : inventory.entrySet()) {
                serverPlayer.getInventory().setItem(entry.getKey(), deserialize(entry));
            }
            //Ender chest
            Map<Integer,String> ender_chest = LocalJsonUtil.StringToEntryMap(resultSet.getString("enderchest"));
            for (Map.Entry<Integer, String> entry : ender_chest.entrySet()) {
                serverPlayer.getEnderChestInventory().setItem(entry.getKey(), deserialize(entry));
            }
            //Effects
            String effectData=resultSet.getString("effects");
            if(effectData.length()>2) {
                serverPlayer.removeAllEffects();
                Map<Integer, String> effects = LocalJsonUtil.StringToEntryMap(effectData);
                for (Map.Entry<Integer, String> entry : effects.entrySet()) {
                    CompoundTag effectTag = NbtUtils.snbtToStructure(entry.getValue().replace("|", ","));
                    MobEffectInstance mobEffectInstance = MobEffectInstance.load(effectTag);
                    assert mobEffectInstance != null;
                    serverPlayer.addEffect(mobEffectInstance);
                }
            }
            //Advancements
            File gameDir = Objects.requireNonNull(serverPlayer.getServer()).getServerDirectory();
            if(Dist.CLIENT.isDedicatedServer()){
                File advancements = new File(gameDir, JdbcConfig.SYNC_WORLD.get().get(0)+"/advancements"+"/"+player_uuid+".json");
                if (!advancements.exists()) {
                    advancements.createNewFile();
                }
                byte [] bytes=resultSet.getString("advancements").getBytes();
                Files.write(advancements.toPath(),bytes);
            }else{
                File[] files= scanAdvancementsFile(player_uuid, gameDir);
                for (File file : files) {
                    if(file==null) continue;
                    byte [] bytes=resultSet.getString("advancements").getBytes();
                    Files.write(file.toPath(),bytes);
                }
            }
        }
        //Mod support
        ModsSupport modsSupport = new ModsSupport();
        modsSupport.onPlayerJoin(serverPlayer);
        serverPlayer.addTag("player_synced");

        resultSet.close();
    }

    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event){
        executorService.submit(()->{
            try {
                doPlayerJoin(event);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

    }

    public static ItemStack deserialize(Map.Entry<Integer, String> entry) throws CommandSyntaxException {
        String nbt= entry.getValue().replace("|",",").replace("^","\"").replace("<","{").replace(">","}").replace("~", "'");
        CompoundTag compoundTag = NbtUtils.snbtToStructure(nbt);
        return ItemStack.of(compoundTag);
    }

    public static String serialize(String object){
        return object.replace(",","|").replace("\"","^").replace("{","<").replace("}",">").replace("'","~");
    }

    public static void doPlayerSaveToFile(PlayerEvent.SaveToFile event) throws SQLException, IOException {
        JDBCsetUp.executeUpdate("UPDATE server_info SET last_update=" + System.currentTimeMillis() + " WHERE id=" + JdbcConfig.SERVER_ID.get());
        if(!event.getEntity().getTags().contains("player_synced")) return;
        store(event.getEntity(),false,Dist.CLIENT.isDedicatedServer());
    }

    @SubscribeEvent
    public static void onPlayerSaveToFile(PlayerEvent.SaveToFile event) {
        executorService.submit(()->{
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
        JDBCsetUp.executeUpdate("UPDATE player_data SET online= '0' WHERE uuid='"+player_uuid+"'");
        store(event.getEntity(),false,Dist.CLIENT.isDedicatedServer());

    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) throws SQLException {
        //Mod support
        ModsSupport modsSupport = new ModsSupport();
        modsSupport.onPlayerLeave(event.getEntity());
        executorService.submit(()->{
            try {
                doPlayerLogout(event);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

    }

    public static void store(Player player, boolean init, boolean isServer) throws SQLException, IOException {
        String player_uuid = player.getUUID().toString();
        //Easy part
        int XP = player.totalExperience;
        int score=player.getScore();
        int food_level=player.getFoodData().getFoodLevel();
        int health=(int) player.getHealth();
        //Equipment
        Map<Integer,String> equipment =new HashMap<>() ;
        for (int i = 0; i < player.getInventory().armor.size(); i++) {
            ItemStack itemStack = player.getInventory().armor.get(i);
            if(itemStack.isEmpty()) continue;
            equipment.put(i,serialize(itemStack.serializeNBT().toString()));
        }
        //inventory
        Inventory inventory = player.getInventory();
        Map<Integer,String> inventoryMap = new HashMap<>();
        for (int i = 0; i < inventory.items.size(); i++) {
            CompoundTag itemNBT = inventory.items.get(i).serializeNBT();
            inventoryMap.put(i,serialize(itemNBT.toString()));
        }
        //EnderChest
        Map<Integer, String> ender_chest = new HashMap<>();
        for (int i=0;i< player.getEnderChestInventory().getContainerSize();i++) {
            CompoundTag itemNBT = player.getEnderChestInventory().getItem(i).serializeNBT();
            ender_chest.put(i,serialize(itemNBT.toString()));
        }
        //Effects
        Map<MobEffect,MobEffectInstance> effects= player.getActiveEffectsMap();
        Map<Integer,String> effectMap=new HashMap<>();
        for (Map.Entry<MobEffect, MobEffectInstance> entry : effects.entrySet()) {
            CompoundTag effectTag= entry.getValue().save(new CompoundTag());
            effectMap.put(MobEffect.getId(entry.getKey()),effectTag.toString().replace(",","|"));
        }
        //Advancements
        //File root = serverPlayer.getServer().getServerDirectory();
        File advancements = null;
        File gameDir = Objects.requireNonNull(player.getServer()).getServerDirectory();
        if(isServer){
            advancements = new File(gameDir, JdbcConfig.SYNC_WORLD.get().get(0)+"/advancements"+"/"+player_uuid+".json");
        }else{
//            File gameDir = Minecraft.getInstance().gameDirectory;
            File[] files= scanAdvancementsFile(player_uuid, gameDir);
            //Get LastModified
            long latestModifiedDate = 0;
            for (File file : files) {
                if(file==null) continue;
                if (file.lastModified() > latestModifiedDate) {
                    latestModifiedDate = file.lastModified();
                    advancements = file;
                }
            }
        }
        byte[] bytes = new byte[0];
        if (advancements != null) {
            bytes = Files.readAllBytes(advancements.toPath());
        }
        String json = new String(bytes, StandardCharsets.UTF_8);

        //SQL Operation
        if(init){
            JDBCsetUp.executeUpdate("INSERT INTO player_data (uuid,armor,inventory,enderchest,advancements,effects,xp,food_level,health,score,online) VALUES ('"+player_uuid+"','"+equipment+"','"+inventoryMap+"','"+ender_chest+"','"+advancements+"','"+effectMap+"','"+XP+"','"+food_level+"','"+health+"','"+score+"',online=true)");
        }else JDBCsetUp.executeUpdate("UPDATE player_data SET inventory = '"+inventoryMap+"',armor='"+equipment+"' ,xp='"+XP+"',effects='"+effectMap+"',enderchest='"+ender_chest+"',score='"+score+"',food_level='"+food_level+"',health='"+health+"',advancements='"+json+"' WHERE uuid = '"+player_uuid+"'");
    }

    private static File[] scanAdvancementsFile(String player_uuid, File gameDir) {
        File[] files = new File[JdbcConfig.SYNC_WORLD.get().size()];
        for (int i = 0; i < JdbcConfig.SYNC_WORLD.get().size(); i++) {
            File advanceFile=new File(gameDir, "saves/"+JdbcConfig.SYNC_WORLD.get().get(i)+"/advancements"+"/"+player_uuid+".json");
            if(!advanceFile.exists()) continue;
            files[i] = advanceFile;
        }
        return files;
    }

    static int tick = 0;

    @SubscribeEvent
    public static void onUpdate(TickEvent.LevelTickEvent event) throws SQLException {
        tick++;
        if(tick == 1800) {
            tick=0;
            long current = System.currentTimeMillis();
            JDBCsetUp.executeUpdate("UPDATE server_info SET last_update ="+current+" WHERE id= "+ JdbcConfig.SERVER_ID.get());
        }
    }

}

