package dev.modzuozhi.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.gameevent.GameEventListenerRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 兜底修复 {@code GameEventDispatcher.post} 中
 * {@code chunkaccess.getListenerRegistry(j2)} 返回 null 导致的 NPE。
 * <p>
 * 背景：细粒度并行子任务线程（或维度 worker）执行实体 tick 时，GlowSquid 等实体的移动会触发
 * {@code GameEventDispatcher.post}。此时读取到的区块对象可能处于"正在生成/加载中"的不一致状态，
 * 其 {@code getListenerRegistry(int)} 可能返回 null，随后 {@code visitInRangeListeners(...)} 直接
 * NPE，进而被 {@code FaultGuard} 计为子任务异常并自动降级关闭细粒度并行（生产环境多次复现：
 * "…because the return value of …ChunkAccess.getListenerRegistry(int) is null"）。
 * <p>
 * 修复：包装该调用，null 时回退到 {@link GameEventListenerRegistry#NOOP}（空实现，行为等同无监听器），
 * 保证任何路径下都不会 NPE，细粒度并行不再因该问题自动降级。
 */
@Mixin(net.minecraft.world.level.gameevent.GameEventDispatcher.class)
public abstract class GameEventDispatcherMixin {

    @WrapOperation(
            method = "post",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getListenerRegistry(I)Lnet/minecraft/world/level/gameevent/GameEventListenerRegistry;"))
    private GameEventListenerRegistry modzuozhi_noopIfNull(
            ChunkAccess chunk, int sectionY, Operation<GameEventListenerRegistry> original) {
        GameEventListenerRegistry registry = original.call(chunk, sectionY);
        return registry != null ? registry : GameEventListenerRegistry.NOOP;
    }
}
