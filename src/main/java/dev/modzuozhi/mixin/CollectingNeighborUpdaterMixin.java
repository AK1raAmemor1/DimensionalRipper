package dev.modzuozhi.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 修复并行随机 tick 触发邻居更新时的并发崩溃（NoSuchElementException）。
 * <p>
 * 根因：第3步区块环境 tick 并行中，藤蔓等 {@code randomTick} 会在子任务线程调用
 * {@code Level.setBlockAndUpdate}，进而触发 {@code CollectingNeighborUpdater}。它内部用
 * 非线程安全的 {@code ArrayDeque} 作为邻居更新栈（{@code stack}/{@code addedThisLayer}），
 * 多个子任务线程并发访问同一实例时会抛 {@code NoSuchElementException}（日志已证实）。
 * <p>
 * 修复：对 {@code CollectingNeighborUpdater} 的每个公开入口方法加<b>实例级重入锁</b>。
 * 同一维度的邻居更新串行执行；不同维度（不同实例）互不干扰。锁为可重入锁，内部递归
 * 更新（{@code executeUpdate → setBlock → 再入 addAndRun}）不会死锁。
 */
@Mixin(CollectingNeighborUpdater.class)
public abstract class CollectingNeighborUpdaterMixin {
    /** 实例级重入锁：串行化同一维度内并发子任务对邻居更新栈的访问。 */
    @Unique
    private final ReentrantLock modzuozhi_neighborLock = new ReentrantLock();

    @WrapMethod(method = "shapeUpdate")
    private void modzuozhi_shapeUpdate(Direction direction, BlockState state, BlockPos pos,
                                       BlockPos neighborPos, int updateFlags, int updateLimit,
                                       Operation<Void> original) {
        this.modzuozhi_neighborLock.lock();
        try {
            original.call(direction, state, pos, neighborPos, updateFlags, updateLimit);
        } finally {
            this.modzuozhi_neighborLock.unlock();
        }
    }

    @WrapMethod(method = "neighborChanged(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/core/BlockPos;)V")
    private void modzuozhi_neighborChanged(BlockPos pos, Block block, BlockPos neighborPos,
                                           Operation<Void> original) {
        this.modzuozhi_neighborLock.lock();
        try {
            original.call(pos, block, neighborPos);
        } finally {
            this.modzuozhi_neighborLock.unlock();
        }
    }

    @WrapMethod(method = "neighborChanged(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/core/BlockPos;Z)V")
    private void modzuozhi_neighborChangedState(BlockState state, BlockPos pos, Block block,
                                                BlockPos neighborPos, boolean movedByPiston,
                                                Operation<Void> original) {
        this.modzuozhi_neighborLock.lock();
        try {
            original.call(state, pos, block, neighborPos, movedByPiston);
        } finally {
            this.modzuozhi_neighborLock.unlock();
        }
    }

    @WrapMethod(method = "updateNeighborsAtExceptFromFacing")
    private void modzuozhi_updateNeighborsAtExceptFromFacing(BlockPos pos, Block block,
                                                             Direction skipDir, Operation<Void> original) {
        this.modzuozhi_neighborLock.lock();
        try {
            original.call(pos, block, skipDir);
        } finally {
            this.modzuozhi_neighborLock.unlock();
        }
    }
}
