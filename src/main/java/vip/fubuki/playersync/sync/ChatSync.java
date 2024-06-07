package vip.fubuki.playersync.sync;

import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.server.players.PlayerList;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import vip.fubuki.playersync.util.JDBCsetUp;

import java.sql.Connection;
import java.sql.PreparedStatement;
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
        String sql = "INSERT INTO chat (player, message, timestamp) VALUES (?, ?, ?)";
        try (Connection connection = JDBCsetUp.getConnection();
             PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
                preparedStatement.setString(1, event.getUsername());
                preparedStatement.setString(2, event.getMessage());
                preparedStatement.setLong(3, current);
                preparedStatement.executeUpdate();
        }
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
            Component textComponents = Component.nullToEmpty(player+": "+message);
            playerList.broadcastMessage(textComponents, ChatType.CHAT, UUID.nameUUIDFromBytes(player.getBytes()));
        }
        resultSet.close();
        queryResult.getConnection().close();
    }
}
