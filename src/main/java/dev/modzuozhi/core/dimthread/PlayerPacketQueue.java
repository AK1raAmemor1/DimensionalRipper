package dev.modzuozhi.core.dimthread;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 第2步：网络包按玩家分派到维度线程的每维度任务队列。
 * <p>
 * 借鉴 Chlorophyll（Unlicense）的 EntityTaskScheduler 思路：把玩家发来的游戏包处理任务
 * 投递到<b>玩家所在维度</b>的队列，由该维度 worker 在维度 tick 收口处统一执行（见
 * {@code ServerLevelPlayerPacketMixin}）。主线程不再逐一执行所有玩家的包 handle，减轻主线程
 * 瓶颈；多维度下各维度的包处理与维度 tick 并行。
 * <p>
 * 线程安全：{@link ConcurrentLinkedQueue} 支持 netty 读线程 / 服务器主线程并发投递；
 * {@link #drain()} 仅由本维度 tick 线程（维度 worker 或单机主线程串行路径）调用一次。
 */
public final class PlayerPacketQueue {
    /** 标记当前线程正在执行玩家包任务（handle 展开中）。 */
    private static final ThreadLocal<Boolean> EXECUTING = new ThreadLocal<>();

    private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();

    /** 投递一个待处理的玩家包任务（任意线程安全）。 */
    public void offer(Runnable task) {
        tasks.add(task);
    }

    /**
     * 串行执行并清空队列（仅本维度 tick 线程调用）。
     * <p>
     * 执行期间设置 {@link #EXECUTING} 标记：玩家包 handle（如
     * {@code ServerGamePacketListenerImpl.handleMovePlayer}）开头会再次调用
     * {@code PacketUtils.ensureRunningOnSameThread}，此时应<b>直接通过</b>（等价原版
     * "已在正确线程"），否则会把包再次投回队列造成无限循环。
     */
    public void drain() {
        Runnable task;
        while ((task = tasks.poll()) != null) {
            EXECUTING.set(Boolean.TRUE);
            try {
                task.run();
            } catch (Throwable t) {
                // 包任务异常不应中断维度 tick 收口；记录后继续处理剩余包
                DimThreadCore.LOGGER.warn("[PlayerPacketQueue] 玩家包处理异常", t);
            } finally {
                EXECUTING.remove();
            }
        }
    }

    /** 当前线程是否正在执行玩家包任务（handle 展开中）。 */
    public static boolean isExecuting() {
        return Boolean.TRUE.equals(EXECUTING.get());
    }

    public boolean isEmpty() {
        return tasks.isEmpty();
    }
}
