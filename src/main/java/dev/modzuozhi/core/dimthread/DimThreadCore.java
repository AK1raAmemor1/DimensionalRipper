package dev.modzuozhi.core.dimthread;

import dev.modzuozhi.ModZuozhi;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

/**
 * dimthreads 式核心入口：临时主线程交换 + worker 识别 + 线程池托管。
 * <p>
 * 核心思想：不固定"每维度专属线程"，而是所有维度共用一个线程池，主线程每 tick 把
 * 每个 {@code level.tick()} 分发到池中并行，然后 {@code awaitCompletion()} 等待全部完成。
 * 运行某维度 tick 前，将该维度的 {@code thread} 与 {@code chunkSource.mainThread} 临时
 * 设为当前 worker，使原版线程检查自然通过，从根上消除 supplyAsync/join 死锁。
 */
public final class DimThreadCore {
    public static final String MOD_ID = ModZuozhi.MOD_ID;
    public static final Logger LOGGER = ModZuozhi.LOGGER;
    public static final ServerManager MANAGER = new ServerManager();

    private DimThreadCore() {
    }

    public static ThreadPool getThreadPool(MinecraftServer server) {
        return MANAGER.getThreadPool(server);
    }

    /**
     * 临时把一批对象（Level / ServerChunkCache）的 mainThread 换成当前线程，执行任务后还原。
     * 必须在 try/finally 中调用，保证 worker 线程异常时也能还原。
     */
    public static void swapThreadsAndRun(Runnable task, Object... threadedObjects) {
        Thread currentThread = Thread.currentThread();
        Thread[] oldThreads = new Thread[threadedObjects.length];

        for (int i = 0; i < oldThreads.length; i++) {
            oldThreads[i] = ((IMutableMainThread) threadedObjects[i]).dimThreads$getMainThread();
            ((IMutableMainThread) threadedObjects[i]).dimThreads$setMainThread(currentThread);
        }

        try {
            task.run();
        } finally {
            for (int i = 0; i < oldThreads.length; i++) {
                ((IMutableMainThread) threadedObjects[i]).dimThreads$setMainThread(oldThreads[i]);
            }
        }
    }

    /** 给 worker 线程命名，便于在崩溃报告中识别。 */
    public static void attach(Thread thread, String name) {
        thread.setName(MOD_ID + "_server_" + name);
    }

    public static void attach(Thread thread, ServerLevel world) {
        attach(thread, world.dimension().location().getPath());
    }

    /** 判断线程是否为维度 worker（通过名字前缀识别）。 */
    public static boolean owns(Thread thread) {
        return thread.getName().startsWith(MOD_ID + "_server_");
    }
}