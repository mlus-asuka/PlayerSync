package vip.fubuki.playersync.sync.chat;

import vip.fubuki.playersync.config.JdbcConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ChatSyncServer {
    static ServerSocket serverSocket;
    static final Set<Socket> SocketList = ConcurrentHashMap.newKeySet();
    static final ExecutorService executorService = Executors.newCachedThreadPool();

    public void run() throws IOException {
        serverSocket = new ServerSocket(JdbcConfig.CHAT_SERVER_PORT.get());
        while (!Thread.currentThread().isInterrupted()) {
            Socket newSocket = serverSocket.accept();
            SocketList.add(newSocket);
            executorService.submit(() -> handleClient(newSocket));
        }
        serverSocket.close();
    }

    private void handleClient(Socket socket) {
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

    private void broadcastMessage(Socket sender, String message) {
        for (Socket socket : SocketList) {
            if (!socket.equals(sender)) {
                try {
                    OutputStream outputStream = socket.getOutputStream();
                    outputStream.write(message.getBytes());
                    outputStream.flush();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
