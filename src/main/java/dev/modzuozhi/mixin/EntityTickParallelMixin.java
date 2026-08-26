package dev.modzuozhi.mixin;

import dev.modzuozhi.core.dimthread.EntityTickParallel;
import dev.modzuozhi.core.filter.EntityTickFilter;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/**
 * 第4步：实体 tick 并行——拦截 {@code Level.guardEntityTick}，把白名单实体的 tick 提交到
 * 实体子任务屏障并行执行（借鉴 MCMT 思路，写我们自己的实现）。
 * <p>
 * 玩家始终走<b>串行</b>：{@code EntityTickFilter.isParallel} 对玩家恒为 false，因此玩家
 * 在维度 worker 的实体循环里按原版顺序 tick，绝不进入并行批。
 * <p>
 * <b>注意</b>：不能再在 worker 上跳过玩家的实体 tick。1.21.1 的
 * {@code PlayerList.tick()} 并不调用 {@code ServerPlayer.tick()}（其只做延迟统计广播），
 * 玩家实体 tick 唯一路径就是 {@code ServerLevel.tick} 的实体循环（维度 worker 执行）。
 * 若在这里跳过玩家，{@code ServerPlayer.tick()}（含 {@code AbstractContainerMenu
 * .broadcastChanges()}、传送门、状态推进）将<b>从不执行</b> → 熔炉/漏斗等容器 UI 进度条与
 * 物品数量永不刷新（实测），玩家相关游戏逻辑全部停滞。
 */
@Mixin(Level.class)
public abstract class EntityTickParallelMixin {

    @Inject(method = "guardEntityTick", at = @At("HEAD"), cancellable = true)
    private void modzuozhi_guardEntityTick(Consumer<Entity> consumer, Entity entity, CallbackInfo ci) {
        if (EntityTickParallel.current() == null || !EntityTickFilter.isParallel(entity)) {
            // 非并行窗口或非白名单实体（含玩家）：走原版串行（guardEntityTick 原文）
            return;
        }
        // 首次启动 / 区块生成高峰：实体所在区块尚未 FULL 时跳过本 tick。
        // 原因：并行子任务线程对未生成区块做碰撞检测会拿到空气占位（见 ServerChunkCacheMixin
        // 的非阻塞占位设计，为避免 join 生成死锁），村民会被持续下坠拉穿地面遁地；区块 FULL
        // 后下一 tick 恢复正常。此处既不收集并行、也不走串行（串行在 worker 上 join 等生成
        // 会死锁），直接跳过是唯一安全选择。
        if (!modzuozhi_entityChunkReady((ServerLevel) (Object) this, entity)) {
            ci.cancel();
            return;
        }
        MinecraftServer server = ((ServerLevel) (Object) this).getServer();
        // 收集到本 tick 的实体批，循环结束统一按块提交并行（减少逐实体提交的调度/屏障开销）
        EntityTickParallel.collect(server, consumer, entity);
        ci.cancel();
    }

    /**
     * 实体所在区块是否已 FULL（线程无关，直接查 ChunkHolder 的 FULL future）。
     * 与 {@link ServerChunkCacheMixin} 的 subtask 缓存同源，不依赖共享 lastChunk 缓存（防撕裂误判）。
     */
    @Unique
    private static boolean modzuozhi_entityChunkReady(ServerLevel level, Entity entity) {
        long key = ChunkPos.asLong(entity.getBlockX() >> 4, entity.getBlockZ() >> 4);
        try {
            ServerChunkCache cache = level.getChunkSource();
            ChunkHolder holder = ((ServerChunkCacheAccessor) (Object) cache)
                    .modzuozhi_getVisibleChunkIfPresent(key);
            if (holder == null) {
                return false;
            }
            return holder.getFullChunkFuture()
                    .getNow(ChunkHolder.UNLOADED_LEVEL_CHUNK).orElse(null) != null;
        } catch (Throwable t) {
            return false; // 保守：拿不到就跳过本 tick
        }
    }
}