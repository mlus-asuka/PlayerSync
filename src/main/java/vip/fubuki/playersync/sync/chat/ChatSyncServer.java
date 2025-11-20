package vip.fubuki.playersync.sync.chat;

import vip.fubuki.playersync.PlayerSync;
import vip.fubuki.playersync.config.JdbcConfig;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ChatSyncServer {
    static ServerSocket serverSocket;
    static final Set<Socket> SocketList = ConcurrentHashMap.newKeySet();
    static final ExecutorService executorService = Executors.newCachedThreadPool();
    private volatile boolean running = true;

    public void run() throws IOException {
        try {
            serverSocket = new ServerSocket(JdbcConfig.CHAT_SERVER_PORT.get());
            serverSocket.setReuseAddress(true);
            PlayerSync.LOGGER.info("Chat server started successfully on port {}", JdbcConfig.CHAT_SERVER_PORT.get());

            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    Socket newSocket = serverSocket.accept();
                    newSocket.setSoTimeout(0);
                    SocketList.add(newSocket);
                    executorService.submit(() -> handleClient(newSocket));
                    PlayerSync.LOGGER.info("New client connected, total clients: {}", SocketList.size());
                } catch (IOException e) {
                    if (running) {
                        PlayerSync.LOGGER.error("Error accepting client connection: {}", e.getMessage());
                    }
                }
            }
        } finally {
            shutdown();
        }
    }

    private void handleClient(Socket socket) {
        String clientInfo = socket.getInetAddress() + ":" + socket.getPort();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream()))) {

            String message;
            while (running && (message = reader.readLine()) != null) {
                broadcastMessage(socket, message);
            }

        } catch (SocketTimeoutException e) {
            PlayerSync.LOGGER.warn("Client {} timeout", clientInfo);
        } catch (IOException e) {
            PlayerSync.LOGGER.error("Error handling client {}: {}", clientInfo, e.getMessage());
        } finally {
            SocketList.remove(socket);
            try {
                if (!socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException e) {
                PlayerSync.LOGGER.error("Error closing client socket: {}", e.getMessage());
            }
            PlayerSync.LOGGER.info("Client disconnected, remaining clients: {}", SocketList.size());
        }
    }

    private void broadcastMessage(Socket sender, String message) {
        Iterator<Socket> iterator = SocketList.iterator();
        while (iterator.hasNext()) {
            Socket socket = iterator.next();
            if (!socket.equals(sender) && !socket.isClosed()) {
                try {
                    PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
                    writer.println(message);
                } catch (IOException e) {
                    PlayerSync.LOGGER.error("Error broadcasting to client, removing: {}", e.getMessage());
                    iterator.remove();
                    try {
                        socket.close();
                    } catch (IOException ex) {
                        // Ignore
                    }
                }
            }
        }
    }

    public void shutdown() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            PlayerSync.LOGGER.error("Error closing server socket: {}", e.getMessage());
        }

        for (Socket socket : SocketList) {
            try {
                if (!socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException e) {
                // Ignore
            }
        }
        SocketList.clear();

        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
