package dev.modzuozhi.mixin;

import dev.modzuozhi.core.dimthread.DimThreadCore;
import dev.modzuozhi.core.dimthread.DimensionChangeQueue;
import dev.modzuozhi.core.dimthread.TpsTracker;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * 维度独立 tick 循环分发（替代原「有界屏障 awaitCompletion」模型，借鉴 Chlorophyll 思路）。
 * <p>
 * <b>目标</b>：每个 {@link ServerLevel} 一个常驻 {@code LevelTickLoop}，各自按 tick 预算
 * 独立排下一次 tick。某个维度 tick 慢（低 TPS）只会推迟它自己的下一次 tick，不再由主线程
 * 统一 {@code awaitCompletion()} 拖累其它维度，从而实现维度隔离。
 * <p>
 * 主线程 {@code tickChildren} 退化为「协调者」：命令函数、基础连接（登录/状态/配置）、
 * {@code playerList.tick()}、tickables 仍留在主线程；每个维度的「level.tick + 玩家连接 tick
 * + 区块发送/刷新」由该维度 loop 在 worker 线程独立完成（见 {@code ServerConnectionListenerMixin}
 * 跳过游戏连接、{@code LevelTickLoop.tickConnections} 承接游戏连接 tick，杜绝玩家双 tick）。
 * <p>
 * 全局关闭或未激活时，原方法体照常执行（退回原版串行，零副作用）。
 */
@Mixin(value = MinecraftServer.class, priority = 1010)
public abstract class MinecraftServerDimMixin {

    @Shadow
    @Final
    private List<Runnable> tickables;

    /**
     * 主线程每 tick 的协调入口：只做全局部分 + 启动各维度 loop。
     * 命中了维度循环模型的场景才 {@code cancel()} 原方法体。
     */
    @Inject(method = "tickChildren", at = @At("HEAD"), cancellable = true)
    private void modzuozhi_worldizedTickChildren(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        // 无论开关状态，每 tick 记录 TPS（供 /dimensionalripper status 对比优化效果）
        TpsTracker.tick();

        MinecraftServer self = (MinecraftServer) (Object) this;
        if (!DimThreadCore.MANAGER.isActive(self)) {
            return; // 未开启：走原版串行 tickChildren
        }

        // 1. 命令函数（主线程全局）
        self.getFunctions().tick();

        // 2. 启动每个维度的独立 loop（只启动一次，loop 此后自排程）
        for (ServerLevel level : self.getAllLevels()) {
            DimThreadCore.MANAGER.getLoop(level).scheduleIfNeeded();
        }

        // 3. 跨维度传送收口（loop 线程登记的传送，由主线程执行，见 EntityChangeDimensionMixin）
        DimensionChangeQueue.flush();

        // 4. 基础连接（登录/状态/配置）——游戏连接已由维度 loop tick（ServerConnectionListenerMixin）
        self.getConnection().tick();

        // 4.5 连接回收：登录/跨维度/重生后，把每个在线玩家的连接路由到其当前维度 loop（幂等）
        DimThreadCore.MANAGER.reconcileConnections(self);

        // 5. playerList.tick（统计/延迟，主线程）
        self.getPlayerList().tick();

        // 6. tickables（服务器 GUI 刷新等）
        for (Runnable tickable : this.tickables) {
            tickable.run();
        }

        ci.cancel();
    }

    /** 服务器停止时：先等所有维度 tick 收口，再停 loop、关线程池，防止保存与 worker 并发卡死。 */
    @Inject(method = "stopServer", at = @At("HEAD"))
    public void modzuozhi_shutdownThreadpool(CallbackInfo ci) {
        // 必须先等正在执行的维度 tick 完全退出再进入保存：否则主线程 saveAllChunks 会与
        // 仍在飞的维度 worker（level.tick → processUnloads）并发修改 ChunkMap.toDrop
        // （非线程安全 fastutil 结构），导致「正在保存中」无限卡死（只能强杀进程）。
        // stopLoopsAndWaitIdle 先 kill 下一拍、再自旋等 isTicking()==false（30s 看门狗）。
        DimThreadCore.MANAGER.stopLoopsAndWaitIdle();
        DimThreadCore.MANAGER.killAllLoops();
        DimThreadCore.MANAGER.threadPools.forEach((server, pool) -> pool.shutdown());
        DimThreadCore.MANAGER.clear();
    }
}