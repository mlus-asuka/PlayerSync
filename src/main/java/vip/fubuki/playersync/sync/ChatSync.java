package vip.fubuki.playersync.sync;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import org.slf4j.Logger;
import vip.fubuki.playersync.config.JdbcConfig;
import vip.fubuki.playersync.sync.chat.ChatSyncClient;
import vip.fubuki.playersync.sync.chat.ChatSyncServer;

import java.io.IOException;

public class ChatSync {
    public static final Logger LOGGER = LogUtils.getLogger();

    public static void register(){
        if(JdbcConfig.IS_CHAT_SERVER.get()) {
            LOGGER.info("Trying to setup chat server at port " + JdbcConfig.CHAT_SERVER_PORT.get());
            new Thread(()->{
                ChatSyncServer chatSyncServer = new ChatSyncServer();
                try {
                    chatSyncServer.run();
                } catch (IOException e) {
                    LOGGER.error("Unable to start chat server", e);
                }
            }).start();
        }

        new Thread(()->{
            LOGGER.info("Trying to connect to chat server "
                    + JdbcConfig.CHAT_SERVER_IP.get()
                    + ":"
                    + JdbcConfig.CHAT_SERVER_PORT.get());
            ChatSyncClient chatSyncClient = new ChatSyncClient();
            chatSyncClient.run();
        }).start();
        MinecraftForge.EVENT_BUS.register(ChatSyncClient.class);
    }
}
