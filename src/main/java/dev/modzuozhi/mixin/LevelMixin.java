package dev.modzuozhi.mixin;

import dev.modzuozhi.core.dimthread.DimThreadCore;
import dev.modzuozhi.core.dimthread.FineGrainScheduler;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * dimthreads 式 {@code Level.getBlockEntity} 线程守卫伪装。
 * <p>
 * 原版 {@code Level.getBlockEntity} 有线程守卫：服务端且当前线程 != 世界构造时捕获的
 * {@code thread} 时返回 null。维度并行下，整个 {@code ServerLevel.tick} 运行在池中 worker 上，
 * 该守卫会把 worker 的一切 {@code getBlockEntity} 返回 null，导致漏斗/箱子合并/比较器等
 * 依赖 {@code getBlockEntity} 的逻辑失效（漏斗 NPE 等）。
 * <p>
 * 在 {@code swapThreadsAndRun} 期间本维度的 {@code thread} 已被设为当前 worker，守卫本应通过；
 * 这里额外处理「另一维度 worker 访问本维度方块实体」的情况：当调用者（t）与属主（this.thread）
 * 都是维度 worker 时，把当前线程伪装成属主线程，使守卫通过（属主 worker 此刻 tick 本维度，
 * 是唯一写入者，读方直接同步读取安全）。
 */
@Mixin(Level.class)
public abstract class LevelMixin {
    @Shadow
    public Thread thread;

    /**
     * 将 {@code Level.random}（及 {@code randValue}）初始化为线程安全随机源。
     * <p>
     * 原版 {@code Level.random = RandomSource.create()} 得到 {@code LegacyRandomSource}，
     * 其内部通过 {@code ThreadingDetector} 检测跨线程访问并抛异常。维度并行/细粒度并行下，
     * 实体 AI（村民、群游鱼等）与服务器主线程会并发访问 {@code getRandom()}，导致
     * "Accessing LegacyRandomSource from multiple threads"。这里重定向构造期间的
     * {@code RandomSource.create()} 为 {@code createThreadSafe()}（{@code ThreadSafeLegacyRandomSource}，
     * 基于 AtomicLong CAS，无线程检测），使并发访问安全。
     */
    @Redirect(method = "<init>",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;create()Lnet/minecraft/util/RandomSource;"))
    private static RandomSource modzuozhi_threadSafeRandom() {
        return RandomSource.createThreadSafe();
    }

    @Redirect(method = "getBlockEntity",
            at = @At(value = "INVOKE", target = "Ljava/lang/Thread;currentThread()Ljava/lang/Thread;"))
    private Thread modzuozhi_recallForDimthreads() {
        Thread t = Thread.currentThread();
        // 维度 worker 访问本维度方块实体时，伪装成属主线程，使守卫通过；
        // 细粒度子任务线程（subtask）同样需要伪装，否则 getBlockEntity 返回 null
        boolean callerIsActive = DimThreadCore.owns(t) || FineGrainScheduler.inSubTask();
        boolean ownerIsActive = DimThreadCore.owns(this.thread);
        return callerIsActive && ownerIsActive ? this.thread : t;
    }
}
