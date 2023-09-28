package vip.fubuki.playersync.sync;

import net.minecraft.server.management.PlayerList;
import net.minecraft.util.text.ChatType;
import net.minecraft.util.text.ITextComponent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import vip.fubuki.playersync.util.JDBCsetUp;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

@Mod.EventBusSubscriber
public class ChatSync {
    static int tick = 0;
    static long current = System.currentTimeMillis();

    static PlayerList playerList;

    public static void register(){
    }

    @SubscribeEvent
    public static void onPlayerChat(net.minecraftforge.event.ServerChatEvent event) throws SQLException {
        JDBCsetUp.executeUpdate("INSERT INTO chat (player, message, timestamp) VALUES ('" + event.getUsername() + "', '" + event.getMessage() + "', '" + current + "')");
    }

    @SubscribeEvent
    public static void Tick(net.minecraftforge.event.TickEvent.ServerTickEvent event) throws SQLException {
        tick++;
        if(tick == 20) {
            ReadMessage(playerList);
        }
    }

    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event){
        playerList= Objects.requireNonNull(event.getPlayer().getServer()).getPlayerList();
    }

    @SubscribeEvent
    public static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event){
        playerList= Objects.requireNonNull(event.getPlayer().getServer()).getPlayerList();
    }

    public static void ReadMessage(PlayerList playerList) throws SQLException {
        JDBCsetUp.QueryResult queryResult=JDBCsetUp.executeQuery("SELECT * FROM chat WHERE timestamp > " + current);
        ResultSet resultSet= queryResult.getResultSet();
        current = System.currentTimeMillis();
        tick = 0;
        while(resultSet.next()) {
            String player = resultSet.getString("player");
            String message = resultSet.getString("message");
            ITextComponent textComponents = ITextComponent.nullToEmpty(player+": "+message);
            playerList.broadcastMessage(textComponents, ChatType.CHAT, UUID.nameUUIDFromBytes(player.getBytes()));
        }
        resultSet.close();
        queryResult.getConnection().close();
    }
}
