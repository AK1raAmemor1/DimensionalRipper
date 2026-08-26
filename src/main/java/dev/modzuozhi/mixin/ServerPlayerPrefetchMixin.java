package dev.modzuozhi.mixin;

import dev.modzuozhi.core.dimthread.ChunkPrefetcher;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 阶段 A（步骤 0）：挂载区块预取到玩家 tick。
 * <p>
 * 玩家实体 tick 只由主线程 {@code playerList.tick()} → {@code ServerPlayer.doTick()} 驱动
 * （维度 worker 已跳过玩家 tick，见 {@link EntityTickParallelMixin}），因此本注入天然运行在
 * 主线程，与维度并行无冲突。预取逻辑本身是纯增益、全 try/catch，任何异常都不影响玩家。
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerPrefetchMixin {

    @Inject(method = "doTick", at = @At("HEAD"))
    private void modzuozhi_prefetchChunks(CallbackInfo ci) {
        ChunkPrefetcher.onPlayerTick((ServerPlayer) (Object) this);
    }
}