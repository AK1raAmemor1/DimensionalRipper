package dev.modzuozhi.core.dimthread;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 跨线程延迟执行队列（借鉴 MCMT 的 PostExecutePool 思路，写我们自己的实现）。
 * <p>
 * 细粒度并行下，子任务（如方块实体 tick）运行在其它 worker 线程上，可能触发对
 * {@code Level} 内部非线程安全结构（如 {@code pendingBlockEntityTickers}、
 * {@code pendingFreshBlockEntities}）的写入。为保证安全，这些写入不直接发生，
 * 而是投递到本队列，由<b>维度 worker 线程</b>在本维度 tick 的收口处串行执行
 * （此时所有并行子任务已 {@code await} 完成、无并发）。
 * <p>
 * 线程安全：{@link ConcurrentLinkedQueue} 支持多线程并发投递；{@link #drain()}
 * 只由维度 worker 调用一次。
 */
public final class PostExecuteQueue {
    private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();

    /** 投递一个跨线程延迟执行的回调（任意线程安全）。 */
    public void post(Runnable action) {
        queue.add(action);
    }

    /** 串行执行并清空队列（仅维度 worker 在收口处调用）。 */
    public void drain() {
        Runnable action;
        while ((action = queue.poll()) != null) {
            try {
                action.run();
            } catch (Throwable t) {
                // 延迟回调异常不应中断维度 tick 收口
            }
        }
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }
}
