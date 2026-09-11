package dev.modzuozhi.mixin;

import dev.modzuozhi.ModZuozhi;
import dev.modzuozhi.core.dimthread.ChunkLock;
import dev.modzuozhi.core.dimthread.DimThreadCore;
import dev.modzuozhi.core.dimthread.EntityTickParallel;
import dev.modzuozhi.core.dimthread.FaultGuard;
import dev.modzuozhi.core.dimthread.FineGrainScheduler;
import dev.modzuozhi.core.dimthread.PostExecuteQueue;
import dev.modzuozhi.core.dimthread.ShardGate;
import dev.modzuozhi.core.filter.SerDesFilter;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 方块实体（TE）细粒度并行（借鉴 MCMT 思路，写我们自己的实现）。
 * <p>
 * 在 {@code Level.tickBlockEntities()} 的 while 循环中，把线程安全（经
 * {@link SerDesFilter} 判定可并行）的 TE tick 提交到 {@link FineGrainScheduler.Barrier}
 * 并行执行，其余（黑名单/非 vanilla）串行；循环结束后 {@code await} 收口，再
 * {@code drain} 子任务投递的延迟回调。
 * <p>
 * <b>分片模式</b>：与实体分片同语义——循环内只按方块坐标稳定散列<b>收集</b>到分片数组，
 * 循环结束后<b>每片只 tryAcquire 一次</b>（整片打包为一个子任务，片内串行 tick），
 * 把掉拍粒度从"逐 TE 竞争"（会导致同片第 2 个起的 TE 全部被拒、永不执行）修正为
 * "整片掉拍延后"，与实体 Batch 聚合语义一致。
 * <p>
 * 线程安全关键：并行子任务运行在其它 worker 线程，可能并发写 {@code pendingBlockEntityTickers}
 * 等普通集合。这里拦截 {@code addBlockEntityTicker} / {@code addFreshBlockEntities}，
 * 在并行子任务上下文中把写入投递到 {@link PostExecuteQueue}，由维度 worker 串行执行。
 * <p>
 * 仅在 {@code /dimensionalripper fine on} 且维度并行激活、当前线程为维度 worker 时生效；
 * 关闭时完全退化为原版串行。
 */
@Mixin(Level.class)
public abstract class TickingBlockEntityParallelMixin {
    @Shadow
    private void addBlockEntityTicker(TickingBlockEntity ticker) {
    }

    @Shadow
    private void addFreshBlockEntities(Collection<BlockEntity> entities) {
    }

    @Unique
    private FineGrainScheduler.Barrier modzuozhi_teBarrier;
    @Unique
    private ShardGate modzuozhi_teGate;
    @Unique
    private boolean modzuozhi_teParallel;
    /** 本 tick 按分片收集的待并行 TE（索引 = {@code ShardGate.shardOf(pos)}）。 */
    @Unique
    private List<TickingBlockEntity>[] modzuozhi_teShards;

    @Unique
    private boolean modzuozhi_shouldParallel() {
        if (!FineGrainScheduler.isEnabled()) {
            return false;
        }
        if (!((Object) this instanceof ServerLevel serverLevel)) {
            return false;
        }
        MinecraftServer server = serverLevel.getServer();
        return server != null
                && DimThreadCore.MANAGER.isActive(server)
                && DimThreadCore.owns(Thread.currentThread());
    }

    @Inject(method = "tickBlockEntities", at = @At("HEAD"))
    private void modzuozhi_teBegin(CallbackInfo ci) {
        this.modzuozhi_teParallel = modzuozhi_shouldParallel();
        // 每 tick 重建分片数组（16 槽），防止上一 tick 残留数据串拍
        this.modzuozhi_teShards = new List[ShardGate.SHARDS];
        if (this.modzuozhi_teParallel) {
            this.modzuozhi_teBarrier = FineGrainScheduler.beginBarrier();
            this.modzuozhi_teGate = ShardGate.teGate(((ServerLevel) (Object) this).dimension());
        }
    }

    @Redirect(method = "tickBlockEntities",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/entity/TickingBlockEntity;tick()V"))
    private void modzuozhi_teTick(TickingBlockEntity ticker) {
        if (this.modzuozhi_teParallel && SerDesFilter.INSTANCE.isParallel(ticker)) {
            MinecraftServer server = ((ServerLevel) (Object) this).getServer();
            if (EntityTickParallel.isSharded()) {
                // 分片模式：只收集，不在此处 tryAcquire（同拍冲突会让同片第 2 个起的 TE 全部误判掉拍）。
                int shard = ShardGate.shardOf(ticker.getPos());
                List<TickingBlockEntity> list = this.modzuozhi_teShards[shard];
                if (list == null) {
                    list = new ArrayList<>();
                    this.modzuozhi_teShards[shard] = list;
                }
                list.add(ticker);
            } else {
                // 非分片回退：逐 TE 独立子任务并行（原语义）
                this.modzuozhi_teBarrier.submit(server, ticker::tick, ticker.getPos());
            }
        } else {
            ticker.tick();
        }
    }

    @Inject(method = "tickBlockEntities", at = @At("RETURN"))
    private void modzuozhi_teEnd(CallbackInfo ci) {
        if (this.modzuozhi_teParallel && this.modzuozhi_teBarrier != null) {
            MinecraftServer server = ((ServerLevel) (Object) this).getServer();
            for (int s = 0; s < ShardGate.SHARDS; s++) {
                List<TickingBlockEntity> list = this.modzuozhi_teShards[s];
                if (list == null || list.isEmpty()) {
                    continue;
                }
                // 每片一次 tryAcquire：掉拍 = 上一 tick 该片未完成（真实慢片），整片延后。
                if (this.modzuozhi_teGate.tryAcquire(s)) {
                    this.modzuozhi_teBarrier.submitShard(server, this.modzuozhi_teGate, s,
                            () -> modzuozhi_tickTeShard(list));
                } else {
                    ShardGate.bumpTeMissed();
                    ModZuozhi.LOGGER.debug("[TE] 分片 {} 掉拍(上 tick 未完成)，整片延后 1 tick", s);
                }
            }
            this.modzuozhi_teBarrier.await();
            this.modzuozhi_teBarrier.drain();
            this.modzuozhi_teBarrier = null;
            this.modzuozhi_teGate = null;
            this.modzuozhi_teParallel = false;
        }
        this.modzuozhi_teShards = null;
    }

    /**
     * 分片内逐个 TE tick：保持原逐 TE 提交的区块锁语义（同 chunk 的 TE 可能散列到不同片，
     * 需锁所在区块防止并发改方块），单 TE 异常隔离。
     */
    @Unique
    private void modzuozhi_tickTeShard(List<TickingBlockEntity> list) {
        long t0 = System.nanoTime();
        for (TickingBlockEntity ticker : list) {
            try (AutoCloseable lock = ChunkLock.lock(ticker.getPos())) {
                ticker.tick();
            } catch (Throwable t) {
                ModZuozhi.LOGGER.error("[TE] 分片子任务异常", t);
                FaultGuard.onSubTaskFailure();
            }
        }
        // 观测型掉拍：本片负载没能在单 tick 预算内处理完（慢片），累计一次。
        if (System.nanoTime() - t0 > ShardGate.TICK_BUDGET_NS) {
            ShardGate.bumpTeMissed();
        }
    }

    @Inject(method = "addBlockEntityTicker", at = @At("HEAD"), cancellable = true)
    private void modzuozhi_deferTicker(TickingBlockEntity ticker, CallbackInfo ci) {
        PostExecuteQueue queue = FineGrainScheduler.currentQueue();
        if (queue != null) {
            queue.post(() -> this.addBlockEntityTicker(ticker));
            ci.cancel();
        }
    }

    @Inject(method = "addFreshBlockEntities", at = @At("HEAD"), cancellable = true)
    private void modzuozhi_deferFresh(Collection<BlockEntity> entities, CallbackInfo ci) {
        PostExecuteQueue queue = FineGrainScheduler.currentQueue();
        if (queue != null) {
            queue.post(() -> this.addFreshBlockEntities(entities));
            ci.cancel();
        }
    }
}