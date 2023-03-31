package vip.fubuki.playersync.sync;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import vip.fubuki.playersync.config.JdbcConfig;
import vip.fubuki.playersync.util.JDBCsetUp;
import vip.fubuki.playersync.util.LocalJsonUtil;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

@Mod.EventBusSubscriber
public class VanillaSync {

    @SubscribeEvent
    public static void OnPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) throws SQLException, ClassNotFoundException, InstantiationException, IllegalAccessException, IOException, CommandSyntaxException {
        String player_uuid = event.getEntity().getUUID().toString();
        ResultSet resultSet=JDBCsetUp.executeQuery("SELECT online FROM player_data WHERE uuid='"+player_uuid+"'");
        ServerPlayer serverPlayer = (ServerPlayer) event.getEntity();
        if(!resultSet.next()){
            Store(event.getEntity(),true,Dist.CLIENT.isDedicatedServer());
            return;
        }
        boolean online = resultSet.getBoolean("online");
        resultSet=JDBCsetUp.executeQuery("SELECT * FROM player_data WHERE uuid='"+player_uuid+"'");
        if(online) {
            serverPlayer.connection.disconnect(Component.translatable("player_sync.already_online"));
        }else {
            JDBCsetUp.executeUpdate("UPDATE player_data SET online=true WHERE uuid='"+player_uuid+"'");
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
                        serverPlayer.getInventory().armor.set(entry.getKey(), Deserialize(entry));
                    }
                }
                //Inventory
                Map<Integer,String> inventory = LocalJsonUtil.StringToEntryMap(resultSet.getString("inventory"));
                for (Map.Entry<Integer, String> entry : inventory.entrySet()) {
                    serverPlayer.getInventory().setItem(entry.getKey(),Deserialize(entry));
                }
                //Ender chest
                Map<Integer,String> ender_chest = LocalJsonUtil.StringToEntryMap(resultSet.getString("enderchest"));
                for (Map.Entry<Integer, String> entry : ender_chest.entrySet()) {
                    serverPlayer.getEnderChestInventory().setItem(entry.getKey(),Deserialize(entry));
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
                File gameDir = serverPlayer.getServer().getServerDirectory();
                if(Dist.CLIENT.isDedicatedServer()){
                    File advancements = new File(gameDir, JdbcConfig.SYNC_WORLD.get().get(0)+"/advancements"+"/"+player_uuid+".json");
                    if (!advancements.exists()) {
                        advancements.createNewFile();
                    }
                    byte [] bytes=resultSet.getString("advancements").getBytes();
                    Files.write(advancements.toPath(),bytes);
                }else{
                    File[] files= ScanAdvancementsFile(player_uuid, gameDir);
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
        }
        resultSet.close();
    }

    private static ItemStack Deserialize(Map.Entry<Integer, String> entry) throws CommandSyntaxException {
        String nbt= entry.getValue().replace("|",",");
        CompoundTag compoundTag = NbtUtils.snbtToStructure(nbt);
        return ItemStack.of(compoundTag);
    }
    @SubscribeEvent
    public static void OnPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) throws SQLException, ClassNotFoundException, InstantiationException, IllegalAccessException, IOException {
        String player_uuid = event.getEntity().getUUID().toString();
        JDBCsetUp.executeUpdate("UPDATE player_data SET online=false WHERE uuid='"+player_uuid+"'");
        if(!event.getEntity().getTags().contains("player_synced")) return;
        Store(event.getEntity(),false,Dist.CLIENT.isDedicatedServer());
        //Mod support
        ModsSupport modsSupport = new ModsSupport();
        modsSupport.onPlayerLeave(event.getEntity());
        event.getEntity().removeTag("player_synced");
    }

    public static void Store(Player player, boolean init,boolean isServer) throws SQLException, ClassNotFoundException, InstantiationException, IllegalAccessException, IOException {
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
            equipment.put(i,itemStack.serializeNBT().toString().replace(",","|"));
        }
        //inventory
        Inventory inventory = player.getInventory();
        Map<Integer,String> inventoryMap=new HashMap<>();
        for (int i = 0; i < inventory.items.size(); i++) {
            CompoundTag itemNBT = inventory.items.get(i).serializeNBT();
            inventoryMap.put(i,itemNBT.toString().replace(",","|"));
        }
        //EnderChest
        Map<Integer, String> ender_chest=new HashMap<>();
        for (int i=0;i< player.getEnderChestInventory().getContainerSize();i++) {
            CompoundTag itemNBT = player.getEnderChestInventory().getItem(i).serializeNBT();
            ender_chest.put(i,itemNBT.toString().replace(",","|"));
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
            File[] files=ScanAdvancementsFile(player_uuid, gameDir);
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

    private static File[] ScanAdvancementsFile(String player_uuid, File gameDir) {
        File[] files = new File[JdbcConfig.SYNC_WORLD.get().size()];
        for (int i = 0; i < JdbcConfig.SYNC_WORLD.get().size(); i++) {
            File advanceFile=new File(gameDir, "saves/"+JdbcConfig.SYNC_WORLD.get().get(i)+"/advancements"+"/"+player_uuid+".json");
            if(!advanceFile.exists()) continue;
            files[i] = advanceFile;
        }
        return files;
    }
}

