package dev.modzuozhi.mixin;

import dev.modzuozhi.core.dimthread.DimensionChangeQueue;
import dev.modzuozhi.core.dimthread.DimThreadCore;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.DimensionTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 维度循环模型：玩家跨维度传送收口到主线程。
 * <p>
 * 旧的「有界屏障」模型里，玩家实体 tick 只在主线程（{@code EntityTickParallelMixin} 跳过维度
 * worker 上的玩家），因此下界门触发的 {@code ServerPlayer.changeDimension} 也在主线程执行，天然安全。
 * 切到维度循环后，玩家 {@code doTick()} 由本维度 loop 的 {@code tickConnections()} 在 worker 上触发，
 * 于是 {@code changeDimension} 会在 worker 上直接 {@code removePlayerImmediately} 旧维度 +
 * {@code addDuringTeleport} 新维度，与新维度 worker 并发改写实体容器 → 竞态崩溃。
 * <p>
 * 因此这里与 {@code EntityChangeDimensionMixin}（针对 {@code Entity.changeDimension}，非玩家实体）
 * 平行：当调用线程是维度 worker 时，把真正的变更登记到 {@link DimensionChangeQueue}，由主线程在维度
 * tick 收口后（{@code MinecraftServerDimMixin} 的 {@code DimensionChangeQueue.flush()}）统一执行。
 * 主线程上直接调用（如命令 {@code /execute in}）不经本拦截，行为与原版一致。
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerChangeDimensionMixin {

    /** 在 worker 上触发跨维度时退避到主线程队列，避免并发改写两个维度的实体容器。 */
    @Inject(method = "changeDimension", at = @At("HEAD"), cancellable = true, remap = false)
    private void modzuozhi_deferPlayerDimChange(DimensionTransition transition, CallbackInfoReturnable<Entity> cir) {
        if (!DimThreadCore.MANAGER.isActive(transition.newLevel().getServer())) {
            return;
        }
        if (DimThreadCore.owns(Thread.currentThread())) {
            DimensionChangeQueue.schedule((Entity) (Object) this, transition);
            cir.setReturnValue(null);
        }
    }
}