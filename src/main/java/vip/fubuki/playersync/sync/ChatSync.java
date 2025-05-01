package vip.fubuki.playersync.sync;

import net.minecraft.network.chat.Component;
import net.minecraft.server.players.PlayerList;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import vip.fubuki.playersync.config.JdbcConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Objects;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

public class ChatSync {
    private static final Logger LOGGER = LogUtils.getLogger();

    static PlayerList playerList;

    static ServerSocket serverSocket;
    static Socket clientSocket;
    static Set<Socket> SocketList = ConcurrentHashMap.newKeySet();
    static ExecutorService executorService = Executors.newCachedThreadPool();

    public static void register(){
        if(JdbcConfig.IS_CHAT_SERVER.get()) {
            LOGGER.info("Launching chat server thread.");
            new Thread(ChatSync::ServerSocket).start();
        }
        ClientSocket();
        NeoForge.EVENT_BUS.register(ChatSync.class);
    }


    private static void ServerSocket() {
        try {
            LOGGER.info("Trying to setup chat server at port " + JdbcConfig.CHAT_SERVER_PORT.get());
            serverSocket = new ServerSocket(JdbcConfig.CHAT_SERVER_PORT.get());
            while (true) {
                Socket newSocket = serverSocket.accept();
                SocketList.add(newSocket);
                executorService.submit(() -> handleClient(newSocket));
            }
        } catch (IOException e) {
            LOGGER.error("Unable to start chat server");
            e.printStackTrace();
        } finally {
            try {
                serverSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static void handleClient(Socket socket) {
        try (InputStream inputStream = socket.getInputStream()) {
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                String message = new String(buffer, 0, bytesRead);
                broadcastMessage(socket, message);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            SocketList.remove(socket);
            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static void broadcastMessage(Socket sender, String message) {
        for (Socket socket : SocketList) {
            if (!socket.equals(sender)) {
                try {
                    OutputStream outputStream = socket.getOutputStream();
                    outputStream.write(message.getBytes());
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private static void ClientSocket() {
        try {
            LOGGER.info("Trying to connect to chat server "
                    + JdbcConfig.CHAT_SERVER_IP.get()
                    + ":"
                    + JdbcConfig.CHAT_SERVER_PORT.get());
            clientSocket = new Socket(JdbcConfig.CHAT_SERVER_IP.get(), JdbcConfig.CHAT_SERVER_PORT.get());
            Scanner scanner = new Scanner(clientSocket.getInputStream());
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine();
                Component textComponents = Component.nullToEmpty(line);
                playerList.broadcastSystemMessage(textComponents,true);
            }
        } catch (IOException e) {
            e.printStackTrace();
            reconnectClient();
        }
    }

    private static void reconnectClient() {
        LOGGER.warn("TODO: implement reconnectClient()");
        //TODO
    }

    @SubscribeEvent
    public static void onPlayerChat(net.neoforged.neoforge.event.ServerChatEvent event) throws IOException {
        String message= event.getUsername()+":"+event.getMessage();
        OutputStream outputStream = clientSocket.getOutputStream();
        outputStream.write(message.getBytes());
    }

    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event){
        playerList= Objects.requireNonNull(event.getEntity().getServer()).getPlayerList();
    }

    @SubscribeEvent
    public static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event){
        playerList= Objects.requireNonNull(event.getEntity().getServer()).getPlayerList();
    }
}
