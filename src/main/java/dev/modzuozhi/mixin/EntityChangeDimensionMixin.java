package dev.modzuozhi.mixin;

import dev.modzuozhi.core.dimthread.DimensionChangeQueue;
import dev.modzuozhi.core.dimthread.DimThreadCore;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.DimensionTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.Nullable;

/**
 * dimthreads 式跨维度传送调度。
 * <p>
 * 各维度 tick 并行运行在不同 worker 上，实体容器 {@code Level.entityStorage} 非线程安全。
 * 若某维度 worker 直接调用 {@code changeDimension} 迁移实体到另一维度，会与其他 worker
 * 并发修改实体容器/区块生命周期而崩溃或死锁。因此当调用线程是维度 worker 时，把真正的
 * 迁移登记到 {@link DimensionChangeQueue}，由主线程在维度 tick 收口后、
 * {@code playerList.tick()} 之前统一执行（见 {@code MinecraftServerDimMixin}）。
 * <p>
 * 与 dimthreads 原版用 {@code server.execute()} 的区别：execute 依赖 {@code runAllTasks}
 * 在 tick 结束后才消费，传送会延迟到下一 tick，且如果期间玩家走开会被 stale 传送拉回
 * 传送门（反复拉扯）。本队列在同一 tick 收口处消费，传送即时完成，杜绝拉扯。
 */
@Mixin(Entity.class)
public abstract class EntityChangeDimensionMixin {

    @Shadow
    @Nullable
    public abstract Entity changeDimension(DimensionTransition transition);

    @Inject(method = "changeDimension", at = @At("HEAD"), cancellable = true, remap = false)
    public void modzuozhi_moveToWorld(DimensionTransition transition, CallbackInfoReturnable<Entity> cir) {
        if (!DimThreadCore.MANAGER.isActive(transition.newLevel().getServer())) {
            return;
        }
        if (DimThreadCore.owns(Thread.currentThread())) {
            DimensionChangeQueue.schedule((Entity) (Object) this, transition);
            cir.setReturnValue(null);
        }
    }
}