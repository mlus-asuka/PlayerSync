package vip.fubuki.playersync.sync.chat;

import net.minecraft.network.chat.Component;
import net.minecraft.server.players.PlayerList;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import vip.fubuki.playersync.PlayerSync;
import vip.fubuki.playersync.sync.ChatSync;
import vip.fubuki.playersync.util.PSThreadPoolFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class ChatSyncClient implements AutoCloseable {
    private static final int RECONNECT_DELAY_MS = 5_000;
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static final int CONNECT_TIMEOUT_MS = 15_000;

    private final AtomicReference<PlayerList> playerList = new AtomicReference<>();
    private final AtomicReference<Socket> clientSocket = new AtomicReference<>();
    private final AtomicReference<PrintWriter> out = new AtomicReference<>();
    private final BlockingQueue<String> outgoingQueue = new LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final String host;
    private final int port;
    private volatile Thread readerThread;
    private volatile Thread writerThread;

    public ChatSyncClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        running.set(true);

        PSThreadPoolFactory factory = new PSThreadPoolFactory("ChatSync-Client");
        readerThread = factory.newThread(this::readerLoop);
        readerThread.setDaemon(true);
        readerThread.start();

        writerThread = factory.newThread(this::writerLoop);
        writerThread.setDaemon(true);
        writerThread.start();
    }

    private void readerLoop() {
        int reconnectAttempts = 0;
        while (running.get() && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
            try {
                PlayerSync.LOGGER.info("Connecting to chat server {}:{}", host, port);

                Socket socket = new Socket();
                socket.setReuseAddress(true);
                socket.setKeepAlive(true);
                socket.setTcpNoDelay(true);
                socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                socket.setSoTimeout(0);

                clientSocket.set(socket);
                out.set(new PrintWriter(socket.getOutputStream(), true));

                PlayerSync.LOGGER.info("Successfully connected to chat server {}:{}", host, port);
                reconnectAttempts = 0;

                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                String serverMessage;
                while (running.get() && (serverMessage = in.readLine()) != null) {
                    Component textComponents = Component.nullToEmpty(serverMessage);
                    PlayerList list = playerList.get();
                    if (list != null) {
                        list.getServer().execute(() ->
                                list.broadcastSystemMessage(textComponents, false));
                    } else {
                        PlayerSync.LOGGER.info("Received message from chat server: {}", serverMessage);
                    }
                }
            } catch (SocketTimeoutException e) {
                PlayerSync.LOGGER.warn("Chat server read timeout, reconnecting...");
            } catch (ConnectException e) {
                PlayerSync.LOGGER.warn("Cannot connect to chat server: {}", e.getMessage());
            } catch (IOException e) {
                if (running.get()) {
                    PlayerSync.LOGGER.error("Chat client connection error: {}", e.getMessage());
                }
            } finally {
                closeSocket();
            }

            if (running.get() && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                reconnectAttempts++;
                PlayerSync.LOGGER.warn("Attempting to reconnect to chat server ({}/{})",
                        reconnectAttempts, MAX_RECONNECT_ATTEMPTS);
                long delay = cappedExponentialDelay(reconnectAttempts);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            PlayerSync.LOGGER.error("Chat client gave up reconnecting after {} attempts", MAX_RECONNECT_ATTEMPTS);
        }
    }

    private void writerLoop() {
        try {
            while (running.get() || !outgoingQueue.isEmpty()) {
                String message = outgoingQueue.take();
                PrintWriter writer = out.get();
                if (writer != null) {
                    writer.println(message);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private long cappedExponentialDelay(int attempt) {
        // attempt starts at 1; cap at 60s to avoid overflow
        long multiplier = 1L << Math.min(attempt - 1, 10);
        return Math.min(RECONNECT_DELAY_MS * multiplier, 60_000L);
    }

    private void closeSocket() {
        out.set(null);
        Socket socket = clientSocket.getAndSet(null);
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException e) {
                PlayerSync.LOGGER.error("Error closing chat client socket: {}", e.getMessage());
            }
        }
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        outgoingQueue.offer(""); // unblock writerLoop take()
        closeSocket();

        Thread reader = readerThread;
        Thread writer = writerThread;
        if (reader != null) {
            reader.interrupt();
        }
        if (writer != null) {
            writer.interrupt();
        }
    }

    public void send(String message) {
        if (running.get()) {
            outgoingQueue.offer(message);
        }
    }

    @SubscribeEvent
    public static void onPlayerChat(ServerChatEvent event) {
        ChatSyncClient client = ChatSync.getClient();
        if (client != null) {
            client.send("<" + event.getUsername() + "> " + event.getMessage().getString());
        }
    }

    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        ChatSyncClient client = ChatSync.getClient();
        if (client != null) {
            client.playerList.set(Objects.requireNonNull(event.getEntity().getServer()).getPlayerList());
        }
    }

    @SubscribeEvent
    public static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        ChatSyncClient client = ChatSync.getClient();
        if (client != null) {
            client.playerList.set(Objects.requireNonNull(event.getEntity().getServer()).getPlayerList());
        }
    }
}
