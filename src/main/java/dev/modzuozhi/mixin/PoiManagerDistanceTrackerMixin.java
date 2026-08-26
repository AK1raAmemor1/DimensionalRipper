package dev.modzuozhi.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;

/**
 * POI 距离追踪器并发访问加固（村民 POI 并发访问加固）。
 * <p>
 * {@code PoiManager.DistanceTracker}（继承 {@code SectionTracker → DynamicGraphMinFixedPoint →
 * LeveledPriorityQueue}）内部用普通 {@code LongLinkedOpenHashSet} 队列。细粒度并行下：
 * <ul>
 *     <li>服务器线程每 tick 调用 {@code PoiManager.tick → distanceTracker.runAllUpdates()}；</li>
 *     <li>子任务线程的村民传感器调用 {@code PoiManager.sectionsToVillage → distanceTracker.runAllUpdates()}。</li>
 * </ul>
 * 两路并发改写同一队列，fastutil 哈希表 rehash 时损坏（实测抛
 * {@code ArrayIndexOutOfBoundsException: Index -1 out of bounds ... rehash}），导致崩溃。
 * <p>
 * 修复：把 {@code runAllUpdates}（两路唯一入口，内部完成全部队列处理）加
 * {@code synchronized(this)}，单对象锁、逐调用、无嵌套，无死锁；开销集中在有更新时的
 * 队列处理阶段，可忽略。
 */
@Mixin(targets = "net.minecraft.world.entity.ai.village.poi.PoiManager$DistanceTracker")
public abstract class PoiManagerDistanceTrackerMixin {

    @WrapMethod(method = "runAllUpdates")
    private void modzuozhi_runAllUpdates(Operation<Void> original) {
        synchronized (this) {
            original.call();
        }
    }
}
