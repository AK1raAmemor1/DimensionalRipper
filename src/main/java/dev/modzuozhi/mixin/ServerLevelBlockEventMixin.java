package dev.modzuozhi.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Block Event（方块事件）队列并发访问加固。
 * <p>
 * 方块事件（音符盒/活塞/发射器/铃等延迟动作）经 {@code ServerLevel.blockEvent} 入队、
 * 每 tick {@code runBlockEvents} 出队执行并广播。原版 {@code blockEvents} 是普通非线程安全
 * 的 {@code ObjectLinkedOpenHashSet}。细粒度并行下，并行实体子任务一旦触发 {@code blockEvent}
 * （入队写），会与维度 worker/服务器线程正在 {@code runBlockEvents}（出队删）<b>并发读写</b>
 * 同一哈希集 → CME 或哈希表损坏（fastutil 并发损坏崩溃）。
 * <p>
 * 修复：对访问队列的 3 个方法（{@code blockEvent}/{@code runBlockEvents}/{@code clearBlockEvents}）
 * 加 {@code synchronized(this)}：单对象锁、逐调用、无嵌套，无死锁（方块事件低频，开销可忽略）；
 * 同时保留原版 Set 的<b>去重 + 插入序</b>语义，行为零变化。
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelBlockEventMixin {

    @WrapMethod(method = "blockEvent")
    private void modzuozhi_blockEvent(BlockPos pos, Block block, int type, int data, Operation<Void> original) {
        synchronized (this) {
            original.call(pos, block, type, data);
        }
    }

    @WrapMethod(method = "runBlockEvents")
    private void modzuozhi_runBlockEvents(Operation<Void> original) {
        synchronized (this) {
            original.call();
        }
    }

    @WrapMethod(method = "clearBlockEvents")
    private void modzuozhi_clearBlockEvents(BoundingBox box, Operation<Void> original) {
        synchronized (this) {
            original.call(box);
        }
    }
}
