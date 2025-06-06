package vip.fubuki.playersync.sync.chat;

import net.minecraft.network.chat.Component;
import net.minecraft.server.players.PlayerList;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import vip.fubuki.playersync.PlayerSync;
import vip.fubuki.playersync.config.JdbcConfig;
import vip.fubuki.playersync.sync.ChatSync;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Objects;

public class ChatSyncClient {
    static PlayerList playerList;
    static Socket clientSocket;
    static PrintWriter out;

    public void run() {
        try {
            clientSocket = new Socket(JdbcConfig.CHAT_SERVER_IP.get(), JdbcConfig.CHAT_SERVER_PORT.get());
            out = new PrintWriter(clientSocket.getOutputStream(),true);

            BufferedReader in = new BufferedReader(
                    new InputStreamReader(clientSocket.getInputStream()));

            String serverMessage;
            while ((serverMessage = in.readLine()) != null) {
                PlayerSync.LOGGER.info("Received message from chat server: " + serverMessage);
                Component textComponents = Component.nullToEmpty(serverMessage);
                if(playerList!=null){
                    playerList.broadcastSystemMessage(textComponents,false);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
            reconnectClient();
        }
    }

    private void reconnectClient() {
        ChatSync.LOGGER.warn("TODO: implement reconnectClient()");
        //TODO
    }

    @SubscribeEvent
    public static void onPlayerChat(net.minecraftforge.event.ServerChatEvent event) {
        String message= "<"+event.getUsername()+"> "+event.getMessage().getString();
        if (out != null) {
            out.println(message);
        }
    }

    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event){
        playerList = Objects.requireNonNull(event.getEntity().getServer()).getPlayerList();
    }

    @SubscribeEvent
    public static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event){
        playerList = Objects.requireNonNull(event.getEntity().getServer()).getPlayerList();
    }
}
