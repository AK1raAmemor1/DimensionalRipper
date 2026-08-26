package dev.modzuozhi.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.ExpirableValue;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import org.spongepowered.asm.mixin.Mixin;

import java.util.Optional;

/**
 * 跨线程访问生物大脑内存的同步加固（借鉴 Async/MCMT 思路，独立实现）。
 * <p>
 * 细粒度并行时，多个实体的 tick 在子任务线程上并发执行。某个生物的大脑内存
 * （{@code Brain.memories}）不仅被它自己的 tick 读写，还可能被<b>其它实体</b>的
 * 传感器/寻路并发访问（如 {@code NearestLivingEntitySensor} 读取它的记忆）。
 * 原版 {@code Brain} 用普通 HashMap，跨线程读写存在数据竞争（潜在错误行为而非崩溃）。
 * <p>
 * 这里对记忆读写入口加 {@code synchronized(this)}：单对象锁、逐方法加解锁、无嵌套，
 * 同一大脑同一时刻只允许一个线程访问，结构上无死锁；未竞争时偏向锁开销可忽略。
 * 相比给整段 villager tick 加锁，仅同步记忆访问点，保留并行收益。
 */
@Mixin(Brain.class)
public abstract class BrainMixin {

    @WrapMethod(method = "getMemory")
    private <U> Optional<U> modzuozhi_getMemory(MemoryModuleType<U> type, Operation<Optional<U>> original) {
        synchronized (this) {
            return original.call(type);
        }
    }

    @WrapMethod(method = "getMemoryInternal")
    private <U> Optional<U> modzuozhi_getMemoryInternal(MemoryModuleType<U> type, Operation<Optional<U>> original) {
        synchronized (this) {
            return original.call(type);
        }
    }

    @WrapMethod(method = "hasMemoryValue")
    private boolean modzuozhi_hasMemoryValue(MemoryModuleType<?> type, Operation<Boolean> original) {
        synchronized (this) {
            return original.call(type);
        }
    }

    @WrapMethod(method = "checkMemory")
    private boolean modzuozhi_checkMemory(MemoryModuleType<?> type, MemoryStatus status, Operation<Boolean> original) {
        synchronized (this) {
            return original.call(type, status);
        }
    }

    @WrapMethod(method = "setMemoryInternal")
    private <U> void modzuozhi_setMemoryInternal(
            MemoryModuleType<U> type, Optional<? extends ExpirableValue<?>> value, Operation<Void> original) {
        synchronized (this) {
            original.call(type, value);
        }
    }

    @WrapMethod(method = "clearMemories")
    private void modzuozhi_clearMemories(Operation<Void> original) {
        synchronized (this) {
            original.call();
        }
    }
}
