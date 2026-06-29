package vip.fubuki.playersync.sync.chat;

import vip.fubuki.playersync.PlayerSync;
import vip.fubuki.playersync.util.PSThreadPoolFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class ChatSyncServer implements AutoCloseable {
    private final int port;
    private final Set<ClientHandle> clients = ConcurrentHashMap.newKeySet();
    private final ExecutorService executorService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile ServerSocket serverSocket;
    private volatile Thread acceptThread;

    public ChatSyncServer(int port) {
        this.port = port;
        this.executorService = Executors.newCachedThreadPool(new PSThreadPoolFactory("ChatSync-Server"));
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        running.set(true);
        PSThreadPoolFactory factory = new PSThreadPoolFactory("ChatSync-Accept");
        acceptThread = factory.newThread(this::acceptLoop);
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private void acceptLoop() {
        try (ServerSocket socket = new ServerSocket(port)) {
            socket.setReuseAddress(true);
            serverSocket = socket;
            PlayerSync.LOGGER.info("Chat server started successfully on port {}", port);

            while (running.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    Socket clientSocket = socket.accept();
                    clientSocket.setSoTimeout(0);
                    ClientHandle handle = new ClientHandle(clientSocket, this);
                    clients.add(handle);
                    executorService.submit(handle::run);
                    PlayerSync.LOGGER.info("New chat client connected, total clients: {}", clients.size());
                } catch (IOException e) {
                    if (running.get()) {
                        PlayerSync.LOGGER.error("Error accepting chat client connection: {}", e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            if (running.get()) {
                PlayerSync.LOGGER.error("Chat server failed to bind port {}: {}", port, e.getMessage());
            }
        } finally {
            close();
        }
    }

    void broadcast(ClientHandle sender, String message) {
        for (ClientHandle client : clients) {
            if (client == sender) {
                continue;
            }
            client.send(message);
        }
    }

    void removeClient(ClientHandle client) {
        clients.remove(client);
        client.close();
        PlayerSync.LOGGER.info("Chat client disconnected, remaining clients: {}", clients.size());
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            // Still perform resource cleanup if called explicitly after natural shutdown.
            if (!started.get()) {
                return;
            }
        }

        ServerSocket socket = serverSocket;
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException e) {
                PlayerSync.LOGGER.error("Error closing chat server socket: {}", e.getMessage());
            }
        }

        Thread accept = acceptThread;
        if (accept != null) {
            accept.interrupt();
        }

        for (ClientHandle client : clients) {
            client.close();
        }
        clients.clear();

        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    PlayerSync.LOGGER.error("Chat server executor did not terminate");
                }
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Per-client handler. Holds its own PrintWriter so broadcasts do not create a new
     * writer on every message, and outgoing writes happen only on this client's thread.
     */
    private static final class ClientHandle implements AutoCloseable {
        private final Socket socket;
        private final ChatSyncServer server;
        private final PrintWriter writer;

        ClientHandle(Socket socket, ChatSyncServer server) throws IOException {
            this.socket = socket;
            this.server = server;
            this.writer = new PrintWriter(socket.getOutputStream(), true);
        }

        void run() {
            String clientInfo = socket.getInetAddress() + ":" + socket.getPort();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
                String message;
                while (server.running.get() && (message = reader.readLine()) != null) {
                    server.broadcast(this, message);
                }
            } catch (SocketTimeoutException e) {
                PlayerSync.LOGGER.warn("Chat client {} timeout", clientInfo);
            } catch (IOException e) {
                if (server.running.get()) {
                    PlayerSync.LOGGER.error("Error handling chat client {}: {}", clientInfo, e.getMessage());
                }
            } finally {
                server.removeClient(this);
            }
        }

        void send(String message) {
            if (socket.isClosed()) {
                return;
            }
            writer.println(message);
        }

        @Override
        public void close() {
            if (!socket.isClosed()) {
                try {
                    socket.close();
                } catch (IOException e) {
                    PlayerSync.LOGGER.error("Error closing chat client socket: {}", e.getMessage());
                }
            }
        }
    }
}
