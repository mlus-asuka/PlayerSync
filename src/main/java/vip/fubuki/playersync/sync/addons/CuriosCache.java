package vip.fubuki.playersync.sync.addons;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;

import net.neoforged.fml.ModList;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;
import vip.fubuki.playersync.PlayerSync;
import vip.fubuki.playersync.sync.VanillaSync;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CuriosCache {
    private static final long CACHE_EXPIRY_MS = 3600000;
    public static final ConcurrentHashMap<UUID, CuriosCacheEntry> curiosCache = new ConcurrentHashMap<>();

    public static class CuriosCacheEntry {
        final long timeStamp;
        final String serializedData;

        CuriosCacheEntry(String data) {
            this.timeStamp = System.currentTimeMillis();
            this.serializedData = data;
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timeStamp > CACHE_EXPIRY_MS;
        }
    }

    //If player logged out by "Title Screen" button,you will not be able to get the handlerOpt,and it will make the curios inventory sync failed.
    //Create a method to store temporary curios data when player is dead.
    //Then check player status in the logged out event,and take a normal sync if player is alive.
    //If player is dead or dying,the cache will be used to prevent the empty data from the failure of getting handlerOpt.
    public static void tryStoreCuriosToCache(net.minecraft.world.entity.player.Player player) {
        if (!ModList.get().isLoaded("curios") || !CuriosCache.isKeepInventoryActive(player)) {
            return;
        }

        try {
            Optional<ICuriosItemHandler> handlerOpt = CuriosApi.getCuriosInventory(player);
            if (handlerOpt.isEmpty()) {
                PlayerSync.LOGGER.error("Obtain the curios api failed,cannot create the cache.");
                return;
            }

            ICuriosItemHandler handler = handlerOpt.get();
            String serializedData = serializeCuriosInventory(handler);

            if (serializedData.startsWith("{}")) {
                PlayerSync.LOGGER.debug("No curios data found,skipping the step of creating cache");
                return;
            }

            UUID playerUuid = player.getUUID();
            curiosCache.put(playerUuid, new CuriosCacheEntry(serializedData));
        } catch (Exception e) {
            PlayerSync.LOGGER.error("An error occurred while creating curios cache:" + e.getMessage());
        }
    }

    private static String serializeCuriosInventory(ICuriosItemHandler handler) {
        Map<String, String> flatMap = new HashMap<>();
        try {
            handler.getCurios().forEach((slotType, stacksHandler) -> {
                IDynamicStackHandler dynStacks = stacksHandler.getStacks();
                for (int i = 0; i < dynStacks.getSlots(); i++) {
                    ItemStack stack = dynStacks.getStackInSlot(i);
                    if (!stack.isEmpty()) {
                        String serialized = VanillaSync.serialize(VanillaSync.serializeNBT(stack).toString());
                        flatMap.put(slotType + ":" + i, serialized);
                    }
                }
            });
        } catch (Exception e) {
            PlayerSync.LOGGER.error("Failed to serialize curios data:" + e.getMessage());
        }
        return flatMap.isEmpty() ? "{}" : flatMap.toString();
    }

    public static boolean isKeepInventoryActive(Player player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            PlayerSync.LOGGER.error("Trying to get the gamerule(KeepInventory),but server is null");
            return false;
        }
        return server.getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY);
    }

    public static void RemoveExpiredCuriosCache() {
        long startMs = System.currentTimeMillis();
        int cacheSize = curiosCache.size();

        if (cacheSize == 0) {
            PlayerSync.LOGGER.debug("No curios caches,skipping cleaning");
            return;
        }

        int removed = 0;
        Iterator<Map.Entry<UUID, CuriosCacheEntry>> iterator = curiosCache.entrySet().iterator();

        while (iterator.hasNext()) {
            if (iterator.next().getValue().isExpired()) {
                iterator.remove();
                removed ++;
            }
        }

        if (removed > 0) {
            PlayerSync.LOGGER.info("Cleaned {} curios cache(s),{} left,took {} Ms",
                    removed, curiosCache.size(), System.currentTimeMillis() - startMs);
        }
    }
}
