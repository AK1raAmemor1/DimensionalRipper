package dev.modzuozhi.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 第14步（对齐 Async 零锁路线）：{@code ServerLevel$EntityCallbacks} 的可见性/生命周期回调加
 * {@code synchronized(this)}，替代原全局写锁 {@code SECTION_LOCK} 对这些共享状态的串行保护。
 * <p>
 * 移除全局写锁后，实体 tick 并行子任务会<b>并发</b>触发这些回调（经
 * {@code startTracking/stopTracking/onDestroyed/onSectionChange}）：
 * <ul>
 *     <li>{@code onTrackingStart/onTrackingEnd}：写 {@code ServerChunkCache} 实体追踪表、玩家表、
 *         {@code dragonParts}、游戏事件监听器、NeoForge 事件——非线程安全，需串行；</li>
 *     <li>{@code onDestroyed}：写记分板；</li>
 *     <li>{@code onSectionChange}：更新游戏事件监听器位置。</li>
 * </ul>
 * 用 {@code synchronized(this)}（同一 {@code EntityCallbacks} 实例，即每 ServerLevel 一把轻量锁）
 * 把这些<b>快速</b>回调串行化，锁持有时长远小于整段 onMove，实体移动/生成本体仍无锁并行。
 * {@code onTickingStart/onTickingEnd}（EntityTickList 增删）已在 {@link EntityTickListMixin}
 * 中按子任务上下文延迟执行，此处一并加同步兜底。
 * <p>
 * 服务器线程与维度 worker 的其它区块追踪访问不经过本回调，与移除前一致（本回调原本也未与之互斥），
 * 不引入新的竞争面。
 */
@Mixin(targets = "net.minecraft.server.level.ServerLevel$EntityCallbacks")
public abstract class ServerLevelEntityCallbacksMixin {

    @WrapMethod(method = "onTrackingStart")
    private void modzuozhi_syncTrackingStart(Entity entity, Operation<Void> original) {
        synchronized (this) {
            original.call(entity);
        }
    }

    @WrapMethod(method = "onTrackingEnd")
    private void modzuozhi_syncTrackingEnd(Entity entity, Operation<Void> original) {
        synchronized (this) {
            original.call(entity);
        }
    }

    @WrapMethod(method = "onDestroyed")
    private void modzuozhi_syncDestroyed(Entity entity, Operation<Void> original) {
        synchronized (this) {
            original.call(entity);
        }
    }

    @WrapMethod(method = "onSectionChange")
    private void modzuozhi_syncSectionChange(Entity entity, Operation<Void> original) {
        synchronized (this) {
            original.call(entity);
        }
    }

    @WrapMethod(method = "onTickingStart")
    private void modzuozhi_syncTickingStart(Entity entity, Operation<Void> original) {
        synchronized (this) {
            original.call(entity);
        }
    }

    @WrapMethod(method = "onTickingEnd")
    private void modzuozhi_syncTickingEnd(Entity entity, Operation<Void> original) {
        synchronized (this) {
            original.call(entity);
        }
    }
}
