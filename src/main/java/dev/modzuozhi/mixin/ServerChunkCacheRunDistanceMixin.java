package dev.modzuozhi.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 串行化同一维度的区块距离更新（修复并发 {@code runGenerationTasks} 的 CME 与 claim/release
 * 计数异常）。
 * <p>
 * {@code runDistanceManagerUpdates}（内部：{@code DistanceManager.runAllUpdates} →
 * {@code scheduleGenerationTask} 向 {@code ChunkMap.pendingGenerationTasks}（普通 ArrayList）
 * add；以及 {@code ChunkMap.runGenerationTasks} 遍历 + clear，并对其中的生成任务 claim/release）
 * 必须<b>单线程</b>执行。维度 loop 模型下，该维度 worker（{@code level.tick → ServerChunkCache
 * .tick}）与保存线程（{@code save → runDistanceManagerUpdates}）等路径<b>并发</b>调用时：
 * 同时遍历/修改 {@code pendingGenerationTasks} 抛 {@code ConcurrentModificationException}，
 * 且对同一 {@code GenerationChunkHolder} 重复 claim/release 抛
 * {@code IllegalStateException: More releases than claims}（实测：下界维度 tick 异常 +
 * Worker 线程异常）。
 * <p>
 * 用 {@code synchronized(this)}（每 {@code ServerChunkCache} 一把锁）把所有调用者串行化：
 * 生成任务 add/遍历/claim 互斥，release（ChunkMap 生成线程异步）与 claim 自然配对，计数平衡。
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheRunDistanceMixin {

    @WrapMethod(method = "runDistanceManagerUpdates")
    private boolean modzuozhi_syncRunDistanceUpdates(Operation<Boolean> original) {
        synchronized (this) {
            return original.call();
        }
    }
}
