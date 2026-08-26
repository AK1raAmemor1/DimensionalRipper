package dev.modzuozhi.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.gameevent.GameEventListenerRegistry;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 区块游戏事件监听器注册表并发访问加固。
 * <p>
 * 细粒度并行时，实体 tick 触发 {@code gameEvent → GameEventDispatcher.post →
 * getListenerRegistry}，子任务线程与维度 worker/服务器线程会<b>并发</b>访问
 * {@code LevelChunk.gameEventListenerRegistrySections}（fastutil
 * {@code Int2ObjectOpenHashMap}，非线程安全）。并发 {@code computeIfAbsent} 会在 rehash 时
 * 损坏哈希表（实测抛 {@code ArrayIndexOutOfBoundsException: Index -1 out of bounds ... rehash}），
 * 导致服务器崩溃。
 * <p>
 * 修复：把 {@code getListenerRegistry} 整体加 {@code synchronized(this)}（单块锁、逐调用、
 * 无嵌套，无死锁；gameEvent 发布频率低，开销可忽略），串行化该 map 的所有读写入口
 * （{@code GameEventDispatcher} 与 {@code LevelChunk.add/removeGameEventListener} 都走此方法）。
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {

    @WrapMethod(method = "getListenerRegistry")
    private GameEventListenerRegistry modzuozhi_getListenerRegistry(int sectionY, Operation<GameEventListenerRegistry> original) {
        synchronized (this) {
            return original.call(sectionY);
        }
    }
}
