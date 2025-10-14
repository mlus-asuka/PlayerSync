package vip.fubuki.playersync.sync.chat;

import net.minecraft.network.chat.Component;
import net.minecraft.server.players.PlayerList;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
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

    private static volatile long lastHeartbeat = System.currentTimeMillis();
    private static final long HEARTBEAT_INTERVAL = 15000;

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

                clientSocket.setSoTimeout(30000);

                out = new PrintWriter(new BufferedWriter(
                        new OutputStreamWriter(clientSocket.getOutputStream())), true);

                PlayerSync.LOGGER.info("Successfully connected to chat server");
                reconnectAttempts = 0;
                lastHeartbeat = System.currentTimeMillis();

                startHeartbeatMonitor();

                BufferedReader in = new BufferedReader(
                        new InputStreamReader(clientSocket.getInputStream()));

                String serverMessage;
                while (running && (serverMessage = in.readLine()) != null) {
                    lastHeartbeat = System.currentTimeMillis();

                    if ("<heartbeat>".equals(serverMessage)) {
                        continue;
                    }

                    PlayerSync.LOGGER.info("Received message from chat server: " + serverMessage);
                    Component textComponents = Component.nullToEmpty(serverMessage);
                    if(playerList != null){
                        playerList.getServer().execute(() ->
                                playerList.broadcastSystemMessage(textComponents, false));
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

    private void startHeartbeatMonitor() {
        Thread heartbeatThread = new Thread(() -> {
            while (running && clientSocket != null && !clientSocket.isClosed()) {
                try {
                    Thread.sleep(10000); // 每10秒检查一次

                    long now = System.currentTimeMillis();
                    if (now - lastHeartbeat > HEARTBEAT_INTERVAL) {
                        PlayerSync.LOGGER.warn("No heartbeat for {}ms, sending test message",
                                now - lastHeartbeat);

                        // 发送测试消息检查连接
                        if (out != null) {
                            out.println("<heartbeat>");
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "ChatSync-Heartbeat");
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();
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
