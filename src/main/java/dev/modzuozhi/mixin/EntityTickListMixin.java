package dev.modzuozhi.mixin;

import dev.modzuozhi.core.dimthread.FineGrainScheduler;
import dev.modzuozhi.core.dimthread.PostExecuteQueue;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTickList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 第4步：实体 tick 并行——子任务线程对 {@code EntityTickList} 的写入延迟执行
 * （借鉴 MCMT 思路，写我们自己的实现）。
 * <p>
 * {@code EntityTickList} 的 {@code active} 字段在 {@code forEach} 期间被迭代。
 * 并行子任务中实体可能被移除/生成，触发 {@code EntityCallbacks.onTickingEnd/onTickingStart}
 * 进而调用 {@code entityTickList.add/remove}。这些写入发生在子任务线程（非迭代线程），
 * 直接修改 {@code active} 会破坏并发迭代。因此拦截 add/remove，在子任务上下文中投递到
 * {@link PostExecuteQueue}，由维度 worker 在所有实体子任务完成后统一执行。
 * <p>
 * 非子任务上下文（串行/维度 worker 线程）行为不变，零开销。
 */
@Mixin(EntityTickList.class)
public abstract class EntityTickListMixin {

    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void modzuozhi_deferAdd(Entity entity, CallbackInfo ci) {
        PostExecuteQueue queue = FineGrainScheduler.currentQueue();
        if (queue != null) {
            queue.post(() -> ((EntityTickList) (Object) this).add(entity));
            ci.cancel();
        }
    }

    @Inject(method = "remove", at = @At("HEAD"), cancellable = true)
    private void modzuozhi_deferRemove(Entity entity, CallbackInfo ci) {
        PostExecuteQueue queue = FineGrainScheduler.currentQueue();
        if (queue != null) {
            queue.post(() -> ((EntityTickList) (Object) this).remove(entity));
            ci.cancel();
        }
    }
}