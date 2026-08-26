package dev.modzuozhi.mixin;

import dev.modzuozhi.core.dimthread.DimThreadCore;
import dev.modzuozhi.core.dimthread.FineGrainScheduler;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 修复 {@code ServerChunkCache.getChunk} 的 "{@code Chunk not there when requested}" 崩溃。
 * <p>
 * 维度线程并行驱动区块生命周期后，玩家移动进入新生成的区块时，会出现一种竞态：
 * 该区块已生成完毕、{@code getChunkNow} 能拿到完整 {@link LevelChunk}（数据在），但其
 * vanilla ticket 级别尚未对齐到 FULL（玩家位置 ticket 往往在 {@code doTick} 之后的网络
 * tick 才登记）。此时 {@code getChunk(x, z, FULL, true)} 的
 * {@code getChunkFutureMainThread} 会因 {@code chunkAbsent} 返回
 * {@code UNLOADED_CHUNK_FUTURE}，进而在第 163 行抛
 * {@code IllegalStateException: Chunk not there when requested: Unloaded chunk}。
 * <p>
 * 本 mixin 在 {@code getChunk} 中：当 required 的 future 结果为 fail 时，回退到
 * {@code getChunkNow}——若该区块的完整 {@link LevelChunk} 仍在内存中，直接返回它（数据
 * 正确，返回它比抛异常合理得多），彻底消除这一崩溃类；若确实不在内存（真正未加载），
 * 维持原版抛异常语义。
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheGetChunkFallbackMixin {

    /**
     * 包并 {@code getChunk} 里的 {@code getChunkFutureMainThread(...)} 调用点，
     * 把返回的 future 换成"fail 时回退 getChunkNow"的语义。
     * <p>
     * {@code required=false}（碰撞检测 {@code BlockCollisions → getChunkForCollisions → getChunk(x,z,FULL,false)}）
     * 同样需要该回退：区块已生成但 ticket 尚未对齐 FULL 时，原版会返回 null → 碰撞被整体跳过 →
     * 实体穿墙、遁地、卡进方块窒息。回退到内存中的完整区块后，碰撞检测即可拿到真实方块形状。
     */
    @Redirect(
            method = "getChunk",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerChunkCache;getChunkFutureMainThread(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Ljava/util/concurrent/CompletableFuture;")
    )
    private CompletableFuture<ChunkResult<ChunkAccess>> modzuozhi_getChunkFallback(
            ServerChunkCache instance, int x, int z, ChunkStatus status, boolean required) {
        CompletableFuture<ChunkResult<ChunkAccess>> fut =
                ((ServerChunkCacheAccessor) (Object) instance).modzuozhi_getChunkFutureMainThread(x, z, status, required);
        // 无论 required 与否，future 以 fail 结束时都尝试返回仍驻留内存的完整区块：
        //  required=true  避免 "Chunk not there" 崩溃；
        //  required=false 避免碰撞检测拿到 null 而遁地/穿墙。
        return fut.handle((ChunkResult<ChunkAccess> res, Throwable t) -> {
            if (res != null && res.isSuccess()) {
                return res;
            }
            if (t != null) {
                return ChunkResult.error(t.getMessage() == null ? "Unloaded chunk" : t.getMessage());
            }
            LevelChunk now = modzuozhi_tryGetChunkNow(instance, x, z);
            if (now != null) {
                return ChunkResult.of(now);
            }
            // 区块确实不在内存（未加载）：required 时投递单块 PORTAL ticket 触发异步生成。
            // 用途：维度 worker / 子任务线程执行传送门逻辑（如暮色 TFTeleporter 玩家路径
            // scanIntafeBiomes 扫描 128 格）时，会同步 getChunk(required=true) 请求远处未生成
            // 区块 → future fail → 本 fallback 仍失败 → 原版抛 "Chunk not there" → 传送被
            // 中断。这里只给<strong>被请求的这一块</strong>投 ticket，单块渐进生成（不冻结），
            // 上层 PortalProcessorMixin 捕获异常后延后重试，下一 tick 该块已生成/生成中 →
            // 渐进推进直到传送门逻辑所需的全部区块就绪。
            if (required && modzuozhi_shouldProgressiveGenerate(instance)) {
                instance.addRegionTicket(TicketType.PORTAL, new ChunkPos(x, z), 1,
                        new BlockPos(x << 4, 0, z << 4));
            }
            return res != null ? res : ChunkResult.error("Unloaded chunk");
        });
    }

    /** 是否为维度并行激活下的 worker / 子任务线程（此时需要渐进生成而非同步等待）。 */
    @Unique
    private static boolean modzuozhi_shouldProgressiveGenerate(ServerChunkCache cache) {
        return DimThreadCore.MANAGER.isActive(cache.level.getServer())
                && (DimThreadCore.owns(Thread.currentThread()) || FineGrainScheduler.inSubTask());
    }

    /**
     * required 时安全读取内存中的完整区块；未加载返回 null，绝不抛异常。
     * <p>
     * 不能只依赖 {@code cache.getChunkNow}：它要求 {@code Thread.currentThread() == mainThread}，
     * 而本 fallback 的 {@code handle} 回调可能运行在"补全该 future 的 worker 线程"上（区块仍在
     * 生成/打光，future 未完成），此时 getChunkNow 直接返回 null，即使完整区块已在内存中也会
     * 误判为未加载 → 仍抛 "Chunk not there"。故这里<strong>线程无关</strong>地直接读
     * {@link ChunkHolder#getFullChunkFuture()}，绕过 mainThread 线程校验。
     */
    @Unique
    private static LevelChunk modzuozhi_tryGetChunkNow(ServerChunkCache cache, int x, int z) {
        // 1. 主线程优先：getChunkNow（含 lastChunk 缓存命中）
        try {
            LevelChunk c = cache.getChunkNow(x, z);
            if (c != null) {
                return c;
            }
        } catch (Throwable ignored) {
            // 继续
        }
        // 2. 线程无关回退：直接查 ChunkHolder 的 FULL future
        try {
            ChunkHolder holder = ((ServerChunkCacheAccessor) (Object) cache).modzuozhi_getVisibleChunkIfPresent(ChunkPos.asLong(x, z));
            if (holder != null) {
                LevelChunk lc = holder.getFullChunkFuture().getNow(ChunkHolder.UNLOADED_LEVEL_CHUNK).orElse(null);
                if (lc != null) {
                    return lc;
                }
            }
        } catch (Throwable ignored) {
            // 继续
        }
        return null;
    }
}