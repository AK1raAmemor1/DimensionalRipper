package dev.modzuozhi.mixin;

import dev.modzuozhi.core.dimthread.IMutableMainThread;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 让 {@link Level} 实现 {@link IMutableMainThread}，暴露其 {@code thread} 字段，
 * 供 {@code DimThreadCore.swapThreadsAndRun} 临时迁移主线程引用。
 */
@Mixin(Level.class)
public interface ILevelMixin extends IMutableMainThread {
    @Accessor("thread")
    void dimThreads$setMainThread(Thread t);

    @Accessor("thread")
    Thread dimThreads$getMainThread();
}
