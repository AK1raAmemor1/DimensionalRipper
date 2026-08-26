package dev.modzuozhi.core.filter;

import net.minecraft.world.level.block.entity.TickingBlockEntity;

/**
 * SerDes 过滤器接口（借鉴 MCMT 的 ISerDesFilter）。
 * <p>
 * 决定某个方块实体 tick 是否可以在并行 worker 线程中执行。
 * 返回 {@code true} 表示可并行；返回 {@code false} 表示该方块实体线程不安全，
 * 需回退到主线程串行 tick。
 */
@FunctionalInterface
public interface ISerDesFilter {
    /**
     * 该方块实体 tick 是否可并行执行。
     *
     * @param ticker 待 tick 的方块实体（{@link TickingBlockEntity}）
     * @return true = 可并行；false = 回退主线程串行
     */
    boolean isParallel(TickingBlockEntity ticker);
}