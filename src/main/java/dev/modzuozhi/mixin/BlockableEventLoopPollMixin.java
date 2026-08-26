package dev.modzuozhi.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.modzuozhi.core.dimthread.DimThreadCore;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Queue;

/**
 * 修复 {@link BlockableEventLoop#pollTask()} 的 peek→remove 并发竞态。
 * <p>
 * 原版 {@code pollTask()} 先 {@code peek()}（非空判断）再 {@code remove()}，两步<b>非原子</b>。
 * 维度 loop 模型下，本模组维度 worker（{@code LevelTickLoop.drainChunkTasks} 每拍、以及
 * {@code managedBlock}）与服务器主线程 {@code pollTaskInternal}（瞬态窗口内）可能并发 poll
 * 同一维度的 {@code pendingRunnables}：线程 A {@code peek()} 到元素后、{@code remove()} 前，
 * 线程 B 已 {@code remove()}，A 的 {@code remove()} 空队列抛 {@code NoSuchElementException}
 * （实测：下界维度 tick 异常，{@code FaultGuard} 计数 1 次）。
 * <p>
 * 仅在<b>本模组维度 worker</b>线程上用 {@code poll()}（空返回 null，不抛异常）替代
 * {@code remove()}：并发被抢走时返回 false（跳过本拍，下一拍自愈）。其它线程保持原版行为不变。
 */
@Mixin(BlockableEventLoop.class)
public abstract class BlockableEventLoopPollMixin<R extends Runnable> {

    @Shadow
    @Final
    private Queue<R> pendingRunnables;

    @Shadow
    private int blockingCount;

    @Shadow
    protected abstract boolean shouldRun(R runnable);

    @Shadow
    protected abstract void doRunTask(R task);

    @WrapMethod(method = "pollTask")
    private boolean modzuozhi_safePollTask(Operation<Boolean> original) {
        if (!DimThreadCore.owns(Thread.currentThread())) {
            return original.call();
        }
        R r = this.pendingRunnables.peek();
        if (r == null) {
            return false;
        } else if (this.blockingCount == 0 && !this.shouldRun(r)) {
            return false;
        } else {
            R removed = this.pendingRunnables.poll();
            if (removed == null) {
                return false; // 并发被抢走，跳过本拍（自愈）
            }
            this.doRunTask(removed);
            return true;
        }
    }
}
