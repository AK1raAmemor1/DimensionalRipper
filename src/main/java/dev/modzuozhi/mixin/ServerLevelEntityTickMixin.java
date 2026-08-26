package dev.modzuozhi.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.modzuozhi.core.dimthread.DimThreadCore;
import dev.modzuozhi.core.dimthread.EntityTickParallel;
import dev.modzuozhi.core.dimthread.FineGrainScheduler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTickList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.Consumer;

/**
 * 第4步：实体 tick 并行——在 {@code ServerLevel.tick} 的 {@code entityTickList.forEach} 前后
 * 建立/收口实体子任务屏障（借鉴 MCMT 思路，写我们自己的实现）。
 * <p>
 * 原版实体循环（{@code entityTickList.forEach(lambda)}) 的每实体处理：isRemoved 检查 →
 * checkDespawn → 载具处理 → {@code guardEntityTick(tickNonPassenger, entity)}。其中除
 * {@code guardEntityTick} 外的逻辑为<b>串行预检</b>（改动小、需按序），只有最终实体的
 * {@code tick} 是真正的重负载，适合并行。
 * <p>
 * 由于 {@code guardEntityTick} 调用位于 lambda 内部（合成方法，无法稳定引用），本 mixin 用
 * {@code @WrapOperation} 包住整个 {@code forEach} 调用：进入前建立 ThreadLocal 屏障
 * （{@link EntityTickParallel#begin()}），离开时（finally）收口等待全部实体子任务完成
 * （{@link EntityTickParallel#end()}）。真正的实体 tick 由 {@link EntityTickParallelMixin}
 * 在 {@code guardEntityTick} 处读取该屏障并提交并行。
 * <p>
 * 仅在 {@code /dimensionalripper fine on} 且维度并行激活、当前线程为维度 worker 时生效；
 * 关闭时完全退化为原版串行（零开销）。
 * <p>
 * <b>第14步（对齐 Async）</b>：第12步曾因"并行实体 tick + 碰撞开启"的锁竞争而改为碰撞开启时
 * 强制串行实体 tick；第14步移除全局写锁、并发化 {@code EntityLookup/knownUuids/chunkVisibility}、
 * 并同步实体回调后，实体 tick 无条件走并行（碰撞开启也并行），不再需要该串行回退。
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelEntityTickMixin {

    @Unique
    private boolean modzuozhi_entityShouldParallel() {
        if (!FineGrainScheduler.isEnabled()) {
            return false;
        }
        MinecraftServer server = ((ServerLevel) (Object) this).getServer();
        return server != null
                && DimThreadCore.MANAGER.isActive(server)
                && DimThreadCore.owns(Thread.currentThread());
    }

    /**
     * 包住整个实体 tick 循环：进入前建立实体子任务屏障，离开时（含异常）收口等待完成。
     */
    @WrapOperation(method = "tick(Ljava/util/function/BooleanSupplier;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/entity/EntityTickList;forEach(Ljava/util/function/Consumer;)V"))
    private void modzuozhi_entityLoop(EntityTickList list, Consumer<Entity> consumer, Operation<Void> original) {
        boolean parallel = modzuozhi_entityShouldParallel();
        if (parallel) {
            EntityTickParallel.begin();
        }
        try {
            original.call(list, consumer);
        } finally {
            if (parallel) {
                EntityTickParallel.end();
            }
        }
    }
}
