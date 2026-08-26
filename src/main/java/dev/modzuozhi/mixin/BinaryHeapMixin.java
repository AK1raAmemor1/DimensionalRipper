package dev.modzuozhi.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.level.pathfinder.BinaryHeap;
import net.minecraft.world.level.pathfinder.Node;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 第7步：寻路/AI 并发安全——{@code BinaryHeap} 守卫（借鉴 MCMT 思路，写我们自己的实现）。
 * <p>
 * {@code BinaryHeap} 是 A* 寻路的开放集（open set），{@code Node.heapIdx} 记录节点在堆中的
 * 下标（-1 表示不在堆中）。每个 {@code PathFinder} 拥有<b>独立</b>的 {@code BinaryHeap}，
 * 并行实体各自寻路时堆对象不跨实体共享，因此不会并发读写同一堆。
 * <p>
 * 但并行寻路下仍有<b>防御性</b>需要：若节点重复 {@code insert}（其 {@code heapIdx} 已在堆中），
 * 原版直接抛 {@code IllegalStateException("OW KNOWS!")}；若 {@code upHeap/downHeap} 收到
 * {@code heapIdx == -1} 的节点会数组越界。这些异常在正常串行下不会发生，但在并行子任务的
 * 寻路中，若某处对同一节点的堆状态判定出现竞态，可能触发。为让并行寻路<b>失败不崩溃</b>，
 * 这里加守卫：已入堆的节点直接返回（不重复插入）；堆下标为 -1 时跳过堆调整。
 * <p>
 * 语义影响：仅忽略"重复入堆/对已出堆节点的堆调整"这类非法调用，正常寻路路径完全不受影响。
 */
@Mixin(BinaryHeap.class)
public abstract class BinaryHeapMixin {

    /**
     * {@code insert}：若节点已在堆中（{@code heapIdx >= 0}），跳过插入，避免重复入堆破坏堆结构。
     */
    @WrapMethod(method = "insert")
    private Node modzuozhi_guardInsert(Node node, Operation<Node> original) {
        if (node.heapIdx >= 0) {
            return node;
        }
        return original.call(node);
    }

    /**
     * {@code upHeap}：堆下标为 -1（节点已出堆）时跳过，避免数组越界。
     */
    @WrapMethod(method = "upHeap")
    private void modzuozhi_guardUpHeap(int index, Operation<Void> original) {
        if (index < 0) {
            return;
        }
        original.call(index);
    }

    /**
     * {@code downHeap}：堆下标为 -1（节点已出堆）时跳过，避免数组越界。
     */
    @WrapMethod(method = "downHeap")
    private void modzuozhi_guardDownHeap(int index, Operation<Void> original) {
        if (index < 0) {
            return;
        }
        original.call(index);
    }
}
