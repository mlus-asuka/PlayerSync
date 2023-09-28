package vip.fubuki.playersync.util;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class PSThreadPoolFactory implements ThreadFactory {
    private final AtomicInteger threadIdx = new AtomicInteger(0);

    private final String threadNamePrefix;

    public PSThreadPoolFactory(String Prefix) {
        threadNamePrefix = Prefix;
    }
    @Override
    public Thread newThread(Runnable runnable) {
        Thread thread = new Thread(runnable);
        thread.setName(threadNamePrefix + "-thread-" + threadIdx.getAndIncrement());
        return thread;
    }





}
