package vip.fubuki.playersync.sync.chat;

import net.minecraft.network.chat.Component;
import net.minecraft.server.players.PlayerList;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import vip.fubuki.playersync.PlayerSync;
import vip.fubuki.playersync.config.JdbcConfig;

import java.io.*;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Objects;

public class ChatSyncClient {
    static PlayerList playerList;
    static Socket clientSocket;
    static PrintWriter out;

    private static volatile boolean running = true;
    private static final int RECONNECT_DELAY = 5000;
    private static final int MAX_RECONNECT_ATTEMPTS = 10;

    public void run() {
        int reconnectAttempts = 0;

        while (running && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
            try {
                PlayerSync.LOGGER.info("Connecting to chat server {}:{}",
                        JdbcConfig.CHAT_SERVER_IP.get(),
                        JdbcConfig.CHAT_SERVER_PORT.get());

                clientSocket = new Socket();
                clientSocket.setReuseAddress(true);
                clientSocket.setKeepAlive(true);
                clientSocket.setTcpNoDelay(true);

                clientSocket.connect(
                        new InetSocketAddress(
                                JdbcConfig.CHAT_SERVER_IP.get(),
                                JdbcConfig.CHAT_SERVER_PORT.get()
                        ),
                        15000
                );

                clientSocket.setSoTimeout(0);

                out = new PrintWriter(new BufferedWriter(
                        new OutputStreamWriter(clientSocket.getOutputStream())), true);

                PlayerSync.LOGGER.info("Successfully connected to chat server");
                reconnectAttempts = 0;

                BufferedReader in = new BufferedReader(
                        new InputStreamReader(clientSocket.getInputStream()));

                String serverMessage;
                while (running && (serverMessage = in.readLine()) != null) {
                    Component textComponents = Component.nullToEmpty(serverMessage);
                    if(playerList != null){
                        playerList.getServer().execute(() ->
                                playerList.broadcastSystemMessage(textComponents, false));
                    }else {
                        PlayerSync.LOGGER.info("Received message from chat server: " + serverMessage);
                    }
                }

            } catch (SocketTimeoutException e) {
                PlayerSync.LOGGER.warn("Chat server read timeout, reconnecting...");
            } catch (ConnectException e) {
                PlayerSync.LOGGER.warn("Cannot connect to chat server: {}", e.getMessage());
            } catch (IOException e) {
                PlayerSync.LOGGER.error("Chat client connection error: {}", e.getMessage());
            } finally {
                closeConnection();
            }

            if (running && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                reconnectAttempts++;
                PlayerSync.LOGGER.warn("Attempting to reconnect to chat server ({}/{})",
                        reconnectAttempts, MAX_RECONNECT_ATTEMPTS);

                try {
                    long delay = Math.min(RECONNECT_DELAY * (long)Math.pow(2, reconnectAttempts-1), 60000);
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void closeConnection() {
        try {
            if (out != null) {
                out.close();
                out = null;
            }
            if (clientSocket != null && !clientSocket.isClosed()) {
                clientSocket.close();
                clientSocket = null;
            }
        } catch (IOException e) {
            PlayerSync.LOGGER.error("Error closing connection: {}", e.getMessage());
        }
    }

    public void shutdown() {
        running = false;
        closeConnection();
    }

    @SubscribeEvent
    public static void onPlayerChat(ServerChatEvent event) {
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
