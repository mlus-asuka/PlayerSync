package vip.fubuki.playersync.sync;

import net.minecraft.network.chat.Component;
import net.minecraft.server.players.PlayerList;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import vip.fubuki.playersync.util.JDBCsetUp;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

public class ChatSync {
    static int tick = 0;
    static long current = System.currentTimeMillis();
    @SubscribeEvent
    public static void onPlayerChat(net.minecraftforge.event.ServerChatEvent event) throws SQLException, ClassNotFoundException, InstantiationException, IllegalAccessException {
        ReadMessage(Objects.requireNonNull(event.getPlayer().getServer()).getPlayerList());
        JDBCsetUp.executeUpdate("INSERT INTO chat (player, message, timestamp) VALUES ('" + event.getUsername() + "', '" + event.getMessage() + "', '" + current + "')");
    }

    @SubscribeEvent
    public static void Tick(net.minecraftforge.event.TickEvent.ServerTickEvent event) throws SQLException, ClassNotFoundException, InstantiationException, IllegalAccessException {
        tick++;
        if(tick == 20) {
            ReadMessage(event.getServer().getPlayerList());
        }
    }

    public static void ReadMessage(PlayerList playerList) throws SQLException, ClassNotFoundException, InstantiationException, IllegalAccessException {
        ResultSet resultSet= JDBCsetUp.executeQuery("SELECT * FROM chat WHERE timestamp > " + current);
        current = System.currentTimeMillis();
        tick = 0;
        while(resultSet.next()) {
            String player = resultSet.getString("player");
            String message = resultSet.getString("message");
            Component textComponents = Component.literal(player+": "+message);
            playerList.broadcastSystemMessage(textComponents, true);
        }
        resultSet.close();
    }
}
