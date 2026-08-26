package dev.modzuozhi.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.modzuozhi.core.dimthread.FineGrainScheduler;
import dev.modzuozhi.core.dimthread.PostExecuteQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 第6步：区块调度（{@code LevelTicks}）并发安全——并行子任务中的 tick 调度写入延迟到
 * 维度 worker 收口串行执行（借鉴 MCMT 思路，写我们自己的实现）。
 * <p>
 * 时序背景：{@code ServerLevel.tick} 中 {@code blockTicks.tick / fluidTicks.tick}（收集并执行
 * 计划内 tick）与 {@code runBlockEvents} 都运行在<b>维度 worker 线程</b>（串行阶段），
 * 不在细粒度并行窗口内，天然线程安全。
 * <p>
 * 风险点：<b>并行子任务</b>（方块实体 tick、区块随机 tick）里某些方块/方块实体会调用
 * {@code level.scheduleTick(...)} → {@code LevelTicks.schedule}，多个子任务线程<b>并发写</b>
 * {@code LevelTicks} 内部的 {@code Long2ObjectOpenHashMap}/{@code PriorityQueue}（非线程安全），
 * 会损坏调度结构，导致计划内 tick 丢失、方块行为异常。
 * <p>
 * 修复：在并行子任务上下文（{@link FineGrainScheduler#inSubTask()}）中，把 {@code schedule}
 * 投递到 {@link PostExecuteQueue}，由维度 worker 在本维度 tick 收口处（所有并行子任务已
 * {@code await} 完成、无并发）串行执行。由于收口发生在 {@code blockTicks.tick} 之后，新增
 * 调度会排到<b>下个 tick</b>执行，与原版"当前 tick 之后"语义一致。关闭并行时零开销（串行）。
 * <p>
 * 说明：读方法（{@code hasScheduledTick}/{@code willTickThisTick}）在子任务中几乎不被调用，
 * 且读多写少、正确性可容忍偶发旧值，不做延迟（保持原样）。
 */
@Mixin(LevelTicks.class)
public abstract class LevelTicksMixin<T> {

    @WrapMethod(method = "schedule")
    private void modzuozhi_deferSchedule(ScheduledTick<T> tick, Operation<Void> original) {
        PostExecuteQueue queue = FineGrainScheduler.currentQueue();
        if (queue == null) {
            // 非并行子任务上下文：原版直接调度
            original.call(tick);
            return;
        }
        // 并行子任务：延迟到维度 worker 收口串行调度，避免并发写 LevelTicks 内部结构
        queue.post(() -> original.call(tick));
    }
}
