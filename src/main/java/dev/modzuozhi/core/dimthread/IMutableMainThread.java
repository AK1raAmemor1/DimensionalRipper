package dev.modzuozhi.core.dimthread;

/**
 * 可迁移主线程引用的对象（Level.thread / ServerChunkCache.mainThread）。
 * <p>
 * dimthreads 模型：运行某维度 tick 前，先把该维度 {@code Level.thread} 与
 * {@code ServerChunkCache.mainThread} 临时设成当前 worker 线程，使原版所有
 * {@code currentThread() == mainThread} 判断自然成立，杜绝 supplyAsync/join 死锁。
 */
public interface IMutableMainThread {
    Thread dimThreads$getMainThread();

    void dimThreads$setMainThread(Thread thread);
}