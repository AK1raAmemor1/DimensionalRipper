package dev.modzuozhi.core.dimthread;

import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.*;

import static dev.modzuozhi.core.dimthread.DimThreadCore.MOD_ID;

/**
 * 维度共享线程池（dimthreads 模型）。
 * <p>
 * 所有维度共用一个线程池，通过 {@code activeCount} 计数 + {@link #awaitCompletion()}
 * 让主线程在每 tick 分发完所有维度后等待全部完成。线程均为 daemon，避免阻止服务器停止。
 * <p>
 * 线程数<strong>可动态调整</strong>：{@link #setThreadCount(int)} 会随当前活跃维度数上下浮动，
 * 保证每个维度都有线程并行跑，也能适配运行期由其它 mod 新建的维度。
 */
public class ThreadPool {
    private ThreadPoolExecutor executor;
    private volatile int threadCount;
    private final IntLatch activeCount = new IntLatch();

    public ThreadPool() {
        this(Runtime.getRuntime().availableProcessors());
    }

    public ThreadPool(int threadCount) {
        this.threadCount = Math.max(1, threadCount);
        this.restart();
    }

    public int getThreadCount() {
        return this.threadCount;
    }

    /**
     * 动态调整线程数（至少 1）。使用无界队列，多余任务排队等待，绝不因任务数超过线程数而拒绝。
     * <p>
     * 顺序很重要：{@code setCorePoolSize(c)} 要求 {@code c <= maximumPoolSize}，否则抛
     * {@code IllegalArgumentException}。因此<b>增大</b>线程数时先扩 {@code max} 再扩
     * {@code core}；<b>缩小</b>时先缩 {@code core} 再缩 {@code max}。
     */
    public void setThreadCount(int count) {
        int c = Math.max(1, count);
        if (c == this.threadCount) {
            return;
        }
        this.threadCount = c;
        if (this.executor != null && !this.executor.isShutdown()) {
            if (c > this.executor.getMaximumPoolSize()) {
                this.executor.setMaximumPoolSize(c);
                this.executor.setCorePoolSize(c);
            } else {
                this.executor.setCorePoolSize(c);
                this.executor.setMaximumPoolSize(c);
            }
        }
    }

    public int getActiveCount() {
        return this.activeCount.getCount();
    }

    public ThreadPoolExecutor getExecutor() {
        return this.executor;
    }

    public void execute(Runnable action) {
        this.activeCount.increment();
        try {
            this.executor.execute(() -> {
                try {
                    action.run();
                } finally {
                    this.activeCount.decrement();
                }
            });
        } catch (RejectedExecutionException e) {
            // 池已 shutdown（服务器停机/回收旧池）时会拒绝新任务；若不回滚计数，
            // awaitCompletion 会永久等待一个永远不会被 decrement 的计数 → 保存流程死锁。
            this.activeCount.decrement();
            throw e;
        }
    }

    public <T> void execute(Iterator<T> iterator, Consumer<T> action) {
        iterator.forEachRemaining(t -> this.execute(() -> action.accept(t)));
    }

    public <T> void execute(Iterable<T> iterable, Consumer<T> action) {
        iterable.forEach(t -> this.execute(() -> action.accept(t)));
    }

    public <T> void execute(T[] array, Consumer<T> action) {
        for (T t : array) this.execute(() -> action.accept(t));
    }

    public void awaitFreeThread() {
        this.waitFor(value -> value < this.getThreadCount());
    }

    public void awaitCompletion() {
        this.waitFor(value -> value == 0);
    }

    /**
     * 带超时的完成等待（看门狗用）。
     *
     * @param timeoutMillis 最长等待毫秒数
     * @return true 表示在超时前全部完成；false 表示超时（仍有任务未完成）
     */
    public boolean awaitCompletion(long timeoutMillis) {
        return this.waitFor(value -> value == 0, timeoutMillis);
    }

    public void waitFor(IntPredicate condition) {
        try {
            this.activeCount.waitUntil(condition);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean waitFor(IntPredicate condition, long timeoutMillis) {
        try {
            return this.activeCount.waitUntil(condition, timeoutMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void restart() {
        if (this.executor == null || this.executor.isShutdown()) {
            this.executor = new ThreadPoolExecutor(this.threadCount, this.threadCount,
                    0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), r -> {
                Thread t = new Thread(r);
                t.setDaemon(true);
                t.setName(MOD_ID + "_server_" + "unassigned");
                return t;
            });
        }
    }

    public void shutdown() {
        // 仅 shutdown() 只会拒绝新任务、不回收 core 线程，旧池线程会一直挂在 take() 上占用资源。
        // 因此先归零 core、再用 shutdownNow 中断闲置线程，使其真正退出（旧池此时必然闲置，安全）。
        if (this.executor != null) {
            this.executor.setCorePoolSize(0);
            this.executor.shutdownNow();
        }
    }

    public boolean isShutdown() {
        return this.executor.isShutdown();
    }

    private static class IntLatch {
        private CountDownLatch latch;

        private IntLatch() {
            this(0);
        }

        private IntLatch(int count) {
            this.latch = new CountDownLatch(count);
        }

        private synchronized int getCount() {
            return (int) this.latch.getCount();
        }

        private synchronized void decrement() {
            this.latch.countDown();
            this.notifyAll();
        }

        private synchronized void increment() {
            this.latch = new CountDownLatch((int) this.latch.getCount() + 1);
            this.notifyAll();
        }

        private synchronized void waitUntil(IntPredicate predicate) throws InterruptedException {
            while (!predicate.test(this.getCount())) {
                this.wait();
            }
        }

        private synchronized boolean waitUntil(IntPredicate predicate, long timeoutMillis)
                throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (!predicate.test(this.getCount())) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return false;
                }
                this.wait(remaining);
            }
            return true;
        }
    }
}