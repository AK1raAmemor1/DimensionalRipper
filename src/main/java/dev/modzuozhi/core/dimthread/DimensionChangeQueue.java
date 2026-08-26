package dev.modzuozhi.core.dimthread;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.DimensionTransition;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 跨维度传送延期队列：维度 worker 上触发的 {@code changeDimension} 由本队列承接，
 * 不依赖 {@code server.execute()}（跨 tick 消费），而是由主线程在维度 tick 收口后、
 * {@code playerList.tick()} 之前统一处理，保证传送在同一 tick 内完成。
 * <p>
 * 线程安全：维度 worker 写（{@link #schedule}），主线程读（{@link #flush}），
 * 且主线程 flush 时所有维度 worker 已 awaitCompletion 完成，无竞争。
 */
public final class DimensionChangeQueue {
    private static final Deque<PendingChange> queue = new ArrayDeque<>();

    private DimensionChangeQueue() {
    }

    /** 入队一个待执行的维度变更（由维度 worker 调用）。 */
    public static void schedule(Entity entity, DimensionTransition transition) {
        queue.add(new PendingChange(entity, transition));
    }

    /** 是否有待处理的维度变更。 */
    public static boolean hasPending() {
        return !queue.isEmpty();
    }

    /**
     * 在主线程上执行所有排队的维度变更（由 {@code tickChildren} 收口处调用）。
     * <p>
     * 直接调用 {@link Entity#changeDimension}，该调用在主线程上执行时不会被
     * {@code EntityChangeDimensionMixin} 拦截（主线程不是维度 worker），
     * 完整的维度变更逻辑（移除旧维度、添加新维度、发数据包）同步完成。
     */
    @Nullable
    public static Entity flush() {
        PendingChange change;
        Entity result = null;
        while ((change = queue.pollFirst()) != null) {
            try {
                result = change.entity.changeDimension(change.transition);
            } catch (Throwable t) {
                DimThreadCore.LOGGER.error("[DimensionChangeQueue] 维度变更异常", t);
            }
        }
        return result;
    }

    private record PendingChange(Entity entity, DimensionTransition transition) {
    }
}