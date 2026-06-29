package vip.fubuki.playersync.sync;

import com.mojang.logging.LogUtils;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import vip.fubuki.playersync.config.JdbcConfig;
import vip.fubuki.playersync.sync.chat.ChatSyncClient;
import vip.fubuki.playersync.sync.chat.ChatSyncServer;

import java.util.concurrent.atomic.AtomicBoolean;

public class ChatSync {
    public static final Logger LOGGER = LogUtils.getLogger();
    private static ChatSyncServer chatSyncServer;
    private static ChatSyncClient chatSyncClient;
    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

    public static void register() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }

        if (JdbcConfig.IS_CHAT_SERVER.get()) {
            int port = JdbcConfig.CHAT_SERVER_PORT.get();
            LOGGER.info("Trying to setup chat server at port {}", port);
            chatSyncServer = new ChatSyncServer(port);
            chatSyncServer.start();
        }

        chatSyncClient = new ChatSyncClient(
                JdbcConfig.CHAT_SERVER_IP.get(),
                JdbcConfig.CHAT_SERVER_PORT.get()
        );
        // Delay the client connection so a local server has time to bind first.
        Thread delayedStart = new Thread(() -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            LOGGER.info("Trying to connect to chat server {}:{}",
                    JdbcConfig.CHAT_SERVER_IP.get(),
                    JdbcConfig.CHAT_SERVER_PORT.get());
            chatSyncClient.start();
        }, "ChatSync-Client-DelayedStart");
        delayedStart.setDaemon(true);
        delayedStart.start();

        NeoForge.EVENT_BUS.register(ChatSyncClient.class);
    }

    public static void shutdown() {
        if (chatSyncServer != null) {
            chatSyncServer.close();
            chatSyncServer = null;
        }
        if (chatSyncClient != null) {
            chatSyncClient.close();
            chatSyncClient = null;
        }
    }

    public static ChatSyncClient getClient() {
        return chatSyncClient;
    }
}
