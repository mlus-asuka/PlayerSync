package vip.fubuki.playersync.sync;

import com.mojang.logging.LogUtils;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import vip.fubuki.playersync.config.JdbcConfig;
import vip.fubuki.playersync.sync.chat.ChatSyncClient;
import vip.fubuki.playersync.sync.chat.ChatSyncServer;

import java.io.IOException;

public class ChatSync {
    public static final Logger LOGGER = LogUtils.getLogger();
    private static ChatSyncServer chatSyncServer;
    private static ChatSyncClient chatSyncClient;

    public static void register(){
        if(JdbcConfig.IS_CHAT_SERVER.get()) {
            LOGGER.info("Trying to setup chat server at port " + JdbcConfig.CHAT_SERVER_PORT.get());
            new Thread(()->{
                chatSyncServer = new ChatSyncServer();
                try {
                    chatSyncServer.run();
                } catch (IOException e) {
                    LOGGER.error("Unable to start chat server", e);
                }
            }, "ChatSync-Server").start();
        }

        new Thread(()->{
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            LOGGER.info("Trying to connect to chat server "
                    + JdbcConfig.CHAT_SERVER_IP.get()
                    + ":"
                    + JdbcConfig.CHAT_SERVER_PORT.get());
            chatSyncClient = new ChatSyncClient();
            chatSyncClient.run();
        }, "ChatSync-Client").start();
        NeoForge.EVENT_BUS.register(ChatSyncClient.class);
    }

    public static void shutdown() {
        if (chatSyncServer != null) {
            chatSyncServer.shutdown();
        }
        if (chatSyncClient != null) {
            chatSyncClient.shutdown();
        }
    }
}
