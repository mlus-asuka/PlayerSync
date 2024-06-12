package vip.fubuki.playersync.sync;

import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.server.players.PlayerList;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import vip.fubuki.playersync.config.JdbcConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Objects;
import java.util.Scanner;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ChatSync {

    static PlayerList playerList;

    static ServerSocket serverSocket;
    static Socket clientSocket;
    static Set<Socket> SocketList;
    static ExecutorService executorService = Executors.newCachedThreadPool();

    public static void register(){
        if(JdbcConfig.IS_CHAT_SERVER.get())
            new Thread(ChatSync::ServerSocket).start();
        ClientSocket();
        MinecraftForge.EVENT_BUS.register(ChatSync.class);
    }


    private static void ServerSocket() {
        try {
            serverSocket = new ServerSocket(JdbcConfig.CHAT_SERVER_PORT.get());
            while (true) {
                Socket newSocket = serverSocket.accept();
                SocketList.add(newSocket);
                executorService.submit(() -> handleClient(newSocket));
            }
        } catch (IOException e) {
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
            clientSocket = new Socket(JdbcConfig.CHAT_SERVER_IP.get(), JdbcConfig.CHAT_SERVER_PORT.get());
            Scanner scanner = new Scanner(clientSocket.getInputStream());
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine();
                Component textComponents = Component.nullToEmpty(line);
                playerList.broadcastMessage(textComponents, ChatType.CHAT, UUID.randomUUID());
            }
        } catch (IOException e) {
            e.printStackTrace();
            reconnectClient();
        }
    }

    private static void reconnectClient() {
       //TODO
    }

    @SubscribeEvent
    public static void onPlayerChat(net.minecraftforge.event.ServerChatEvent event) throws IOException {
        String message= event.getUsername()+":"+event.getMessage();
        OutputStream outputStream = clientSocket.getOutputStream();
        outputStream.write(message.getBytes());
    }

    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event){
        playerList= Objects.requireNonNull(event.getPlayer().getServer()).getPlayerList();
    }

    @SubscribeEvent
    public static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event){
        playerList= Objects.requireNonNull(event.getPlayer().getServer()).getPlayerList();
    }
}
