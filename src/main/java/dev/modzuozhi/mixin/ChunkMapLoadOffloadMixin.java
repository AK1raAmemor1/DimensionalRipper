package dev.modzuozhi.mixin;

import dev.modzuozhi.ModZuozhi;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteMaps;
import net.minecraft.Util;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.Executor;

/**
 * 阶段 A（下一步）：把「区块反序列化」从主线程下放到后台线程。
 * <p>
 * 原版 {@code ChunkMap.scheduleChunkLoad} 里，磁盘读 + datafix 已经异步（见
 * {@code readChunk}），但 {@code ChunkSerializer.read}（NBT → ProtoChunk，含方块状态/生物群系
 * 调色板解析，CPU 密集）仍跑在 {@code this.mainThreadExecutor}。这正是进图 / 快速跑图 / 传送时
 * 主线程卡顿的元凶之一——本 mixin 把这一步的执行器切换到 {@link Util#backgroundExecutor()}。
 * <p>
 * 配套加固：下放后 {@code markPosition}/{@code createEmptyChunk} 会从后台线程写
 * {@code chunkTypeCache}（仅用于调试的 chunk-type 缓存，非世界数据），而主线程仍在读/删它，
 * 因此把该缓存包装为同步 Map，消除并发写冲突。
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapLoadOffloadMixin {

    /** 反序列化下放开关（与 {@link ModZuozhi#isEnabled()} 叠加，可一键回退）。Mixin 禁止非私有静态字段。 */
    private static volatile boolean ENABLED = true;

    @Shadow
    @Final
    @Mutable
    private Long2ByteMap chunkTypeCache;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void modzuozhi$makeChunkTypeCacheConcurrent(CallbackInfo ci) {
        this.chunkTypeCache = Long2ByteMaps.synchronize(this.chunkTypeCache);
    }

    @ModifyArg(
        method = "scheduleChunkLoad",
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/concurrent/CompletableFuture;thenApplyAsync(Ljava/util/function/Function;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"
        ),
        index = 1
    )
    private Executor modzuozhi$deserializeOffMainThread(Executor executor) {
        return (ModZuozhi.isEnabled() && ENABLED) ? Util.backgroundExecutor() : executor;
    }
}