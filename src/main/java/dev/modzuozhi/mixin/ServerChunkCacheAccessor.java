package dev.modzuozhi.mixin;

import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 暴露 {@code ServerChunkCache} 的私有方法，供 worker 线程非阻塞地读取已加载区块，
 * 以及供 {@link ServerChunkCacheGetChunkFallbackMixin} 构建"已生成但 ticket 尚未对齐"区块的回退。
 */
@Mixin(ServerChunkCache.class)
public interface ServerChunkCacheAccessor {
    @Invoker("getVisibleChunkIfPresent")
    ChunkHolder modzuozhi_getVisibleChunkIfPresent(long chunkPos);

    @Invoker("getChunkFutureMainThread")
    CompletableFuture<ChunkResult<ChunkAccess>> modzuozhi_getChunkFutureMainThread(
            int x, int z, ChunkStatus status, boolean required);
}