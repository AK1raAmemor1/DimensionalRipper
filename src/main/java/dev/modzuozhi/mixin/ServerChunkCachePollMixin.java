package dev.modzuozhi.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.modzuozhi.core.dimthread.DimThreadCore;
import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 维度循环模型：把区块源「主线程」任务收口到维度 loop，禁止服务器主线程二次 poll。
 * <p>
 * 原版里 {@code ServerChunkCache.pollTask()} 由 {@code MinecraftServer.pollTaskInternal}（主线程
 * waitUntilNextTick 期间）调用，负责距离更新（{@code runDistanceManagerUpdates}）+ 光照调度 +
 * 主线程队列排空。切到维度 loop 后，本维度 {@code level.tick()} 已在 worker 上跑
 * {@code ServerChunkCache.tick → runDistanceManagerUpdates}，若服务器主线程仍为该维度
 * {@code pollTask}，两个线程会并发改写同一 {@code DistanceManager}/{@code ChunkMap}（本次启动即
 * 因此在 {@code DistanceManager.runAllUpdates} 抛了 ConcurrentModificationException）。
 * <p>
 * 因此：当监控开启且当前线程<b>不是</b>维度 worker（即服务器主线程/其它线程）时跳过 poll，
 * 本维度的区块源任务统一由 {@code LevelTickLoop.processMainThreadTasks()} 在 worker 上排空。
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCachePollMixin {

    @WrapMethod(method = "pollTask")
    private boolean modzuozhi_skipServerThreadChunkPoll(Operation<Boolean> original) {
        ServerChunkCache self = (ServerChunkCache) (Object) this;
        // 仅当维度 loop 已启动接管（tickChildren 之后）才跳过主线程 poll；启动阶段 prepareLevels
        // 仍依赖主线程 poll 驱动出生点区块生成，跳过会卡死在「Preparing start region」。
        if (DimThreadCore.MANAGER.isActive(self.level.getServer())
                && DimThreadCore.MANAGER.isLoopScheduled(self.level)
                && !DimThreadCore.owns(Thread.currentThread())) {
            return false;
        }
        return original.call();
    }
}