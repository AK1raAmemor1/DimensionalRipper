package dev.modzuozhi.mixin;

import dev.modzuozhi.ModZuozhi;
import dev.modzuozhi.core.dimthread.DimThreadCore;
import dev.modzuozhi.core.dimthread.FaultGuard;
import dev.modzuozhi.core.dimthread.ThreadPool;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 第3步：自动保存分布化——玩家保存按维度分发到各维度线程并行执行。
 * <p>
 * 原版 {@code PlayerList.saveAll()} 在<strong>主线程串行</strong>遍历所有玩家，逐个
 * {@code playerIo.save(player)} 写玩家数据（+ 统计 + 进度）。玩家多时主线程被同步写盘
 * 阻塞数秒。玩家数据按 UUID 写独立文件，维度间、玩家间均无共享写盘目标，可安全并行。
 * <p>
 * 本 mixin 用 {@code @Inject HEAD + cancellable} 接管 {@code saveAll}（维度并行激活时）：
 * <ol>
 *     <li>把在线玩家按<b>所在维度</b>分组；</li>
 *     <li>每个维度在对应维度 worker 上<b>串行保存</b>该维度玩家（维度内保序、维度间并行）；</li>
 *     <li>{@code awaitCompletion()} 等待全部维度写完。</li>
 * </ol>
 * 与 {@code MinecraftServerSaveMixin}（维度区块/实体保存已并行化）配合，自动保存的
 * savePlayers + saveLevel 都落在各维度线程上，主线程不再亲自执行保存逻辑。
 * <p>
 * 关服时 {@code MinecraftServerDimMixin.stopServer} 已先 shutdown 线程池并 clear，本 mixin
 * 经 {@code DimThreadCore.MANAGER.isActive} 判定失效，自动退回原版串行（安全）。
 */
@Mixin(PlayerList.class)
public abstract class PlayerListSaveMixin {

    @Shadow
    protected abstract void save(ServerPlayer player);

    @Inject(method = "saveAll", at = @At("HEAD"), cancellable = true)
    private void modzuozhi_parallelSaveAll(CallbackInfo ci) {
        PlayerList self = (PlayerList) (Object) this;
        MinecraftServer server = self.getServer();
        if (!DimThreadCore.MANAGER.isActive(server)) {
            return; // 未开启 / 关服收尾：走原版串行
        }

        // 按玩家所在维度分组（等价 save() 内部的 connection==null 提前返回）
        Map<ServerLevel, List<ServerPlayer>> byDimension = new LinkedHashMap<>();
        for (ServerPlayer player : self.getPlayers()) {
            if (player.connection == null) {
                continue;
            }
            byDimension.computeIfAbsent(player.serverLevel(), key -> new ArrayList<>()).add(player);
        }
        if (byDimension.isEmpty()) {
            return; // 无在线玩家：直接走原版（saveAll 空循环，无副作用）
        }

        ThreadPool pool = DimThreadCore.getThreadPool(server);
        AtomicReference<Throwable> crash = new AtomicReference<>();

        byDimension.forEach((level, players) -> pool.execute(() -> {
            DimThreadCore.attach(Thread.currentThread(), level);
            DimThreadCore.swapThreadsAndRun(() -> {
                try {
                    for (ServerPlayer player : players) {
                        this.save(player);
                    }
                } catch (Throwable t) {
                    crash.set(t);
                }
            }, level);
        }));

        if (!pool.awaitCompletion(FaultGuard.AWAIT_TIMEOUT_MS)) {
            ModZuozhi.LOGGER.error("[DimThread] 异步保存玩家超时（>{}ms），跳过等待继续", FaultGuard.AWAIT_TIMEOUT_MS);
        }

        Throwable t = crash.get();
        if (t != null) {
            ModZuozhi.LOGGER.error("[DimThread] 异步保存玩家异常", t);
        }

        ci.cancel();
    }
}
