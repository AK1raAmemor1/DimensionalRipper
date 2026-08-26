package dev.modzuozhi.mixin;

import dev.modzuozhi.ModZuozhi;
import dev.modzuozhi.core.dimthread.DimThreadCore;
import dev.modzuozhi.core.dimthread.FaultGuard;
import dev.modzuozhi.core.dimthread.FineGrainScheduler;
import dev.modzuozhi.core.dimthread.ThreadPool;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.WorldData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 多维度并行保存（dimthreads 式）。
 * <p>
 * 原版 {@code MinecraftServer.saveAllChunks} 在 for 循环里<strong>串行</strong>遍历所有维度，
 * 逐个调用 {@code serverlevel.save(...)} 写区块/实体。当维度多且区块实体量大时，保存耗时线性叠加，
 * 主线程被同步写盘阻塞数秒，表现为 "Can't keep up"。
 * <p>
 * 本 mixin 用 {@code @Inject HEAD + cancellable} 完整接管 {@code saveAllChunks}：
 * <ol>
 *     <li>把每个维度的 {@code serverlevel.save(...)} 分发到线程池<strong>并行执行</strong>（各维度目录独立，写盘互不干扰，安全）；</li>
 *     <li>{@code awaitCompletion()} 等待全部维度写完；</li>
 *     <li>主线程串行补齐原方法下半段的<strong>全局</strong>保存（worldData / boss events / level.dat 标签），
 *         这些共享状态不能并行。</li>
 * </ol>
 * 维度越多、区块实体越多，加速越明显。
 */
@Mixin(value = MinecraftServer.class, priority = 1010)
public abstract class MinecraftServerSaveMixin {
    @Shadow
    @Final
    protected WorldData worldData;
    @Shadow
    @Final
    protected LevelStorageSource.LevelStorageAccess storageSource;

    @Inject(method = "saveAllChunks", at = @At("HEAD"), cancellable = true)
    public void modzuozhi_parallelSaveAllChunks(boolean suppressLog, boolean flush, boolean forced,
                                                CallbackInfoReturnable<Boolean> cir) {
        MinecraftServer self = (MinecraftServer) (Object) this;
        if (!DimThreadCore.MANAGER.isActive(self)) {
            return; // 未开启：走原版串行路径
        }

        // 保存前先排空细粒度子任务，避免残留子任务与实体/区块保存并发访问（残留任务此刻
        // 若卡死，Barrier 超时降级已在维度 loop 侧触发，这里再给一个短等待兜底）。
        FineGrainScheduler.awaitIdle(2000L);

        // 1. 并行保存每个维度的区块/实体（独立写盘）
        List<ServerLevel> levels = new ArrayList<>();
        self.getAllLevels().forEach(levels::add);

        ThreadPool pool = DimThreadCore.getThreadPool(self);
        AtomicReference<Throwable> crash = new AtomicReference<>();

        for (ServerLevel level : levels) {
            pool.execute(() -> {
                DimThreadCore.attach(Thread.currentThread(), level);
                DimThreadCore.swapThreadsAndRun(() -> {
                    try {
                        // 等价于原版 serverlevel.save(null, flush, noSave)
                        level.save(null, flush, level.noSave && !forced);
                    } catch (Throwable t) {
                        crash.set(t);
                    }
                }, level, level.getChunkSource());
            });
        }
        // 带超时等待：某维度保存任务若被卡死的线程持锁阻塞，绝不永久挂起主线程（否则
        // 表现为「正在保存中」死锁无法退出）。
        if (!pool.awaitCompletion(FaultGuard.AWAIT_TIMEOUT_MS)) {
            ModZuozhi.LOGGER.error("[DimThread] 异步保存超时（>{}ms），跳过等待继续主线程全局保存", FaultGuard.AWAIT_TIMEOUT_MS);
        }

        Throwable t = crash.get();
        if (t != null) {
            ModZuozhi.LOGGER.error("[DimThread] 异步保存维度异常", t);
        }

        // 2. 主线程串行补齐全局保存（与原版 saveAllChunks 下半段完全一致）
        ServerLevel overworld = self.overworld();
        ServerLevelData serverleveldata = this.worldData.overworldData();
        serverleveldata.setWorldBorder(overworld.getWorldBorder().createSettings());
        this.worldData.setCustomBossEvents(self.getCustomBossEvents().save(self.registryAccess()));
        this.storageSource.saveDataTag(self.registryAccess(), this.worldData,
                self.getPlayerList().getSingleplayerData());

        if (flush) {
            for (ServerLevel serverlevel : levels) {
                ModZuozhi.LOGGER.info("ThreadedAnvilChunkStorage ({}): All chunks are saved",
                        serverlevel.getChunkSource().chunkMap.getStorageName());
            }
            ModZuozhi.LOGGER.info("ThreadedAnvilChunkStorage: All dimensions are saved");
        }

        cir.setReturnValue(true);
    }
}