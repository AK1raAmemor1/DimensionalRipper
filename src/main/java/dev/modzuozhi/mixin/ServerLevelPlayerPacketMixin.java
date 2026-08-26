package dev.modzuozhi.mixin;

import dev.modzuozhi.ModZuozhi;
import dev.modzuozhi.core.dimthread.IPlayerPacketQueue;
import dev.modzuozhi.core.dimthread.PlayerPacketQueue;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * 第2步：网络包按玩家分派到维度线程——每维度包队列的挂载与 tick 收口。
 * <p>
 * 在 {@code ServerLevel} 上挂一个 {@link PlayerPacketQueue}（玩家发来的包处理任务队列），
 * 并在 {@code ServerLevel.tick} 开头（HEAD）统一执行。两种 tick 路径都覆盖：
 * <ul>
 *     <li><b>维度并行</b>：维度 worker 在 {@code swapThreadsAndRun} 内 tick，包任务在 worker
 *         身份下执行（getBlockEntity 等线程守卫自然通过），与其它维度并行。</li>
 *     <li><b>主线程串行</b>（dimCount &lt;= 1 / 维度并行关闭）：主线程直接 tick，包任务在主线程
 *         执行，行为等价原版。</li>
 * </ul>
 * 全局关闭时 {@code drain} 不执行（空转，零副作用）。
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelPlayerPacketMixin implements IPlayerPacketQueue {

    @Unique
    private final PlayerPacketQueue modzuozhi_playerPacketQueue = new PlayerPacketQueue();

    @Override
    @Unique
    public PlayerPacketQueue modzuozhi$playerPacketQueue() {
        return this.modzuozhi_playerPacketQueue;
    }

    /** 维度 tick 开头：先排空本维度玩家排队的包处理任务（命令等），再进沉重的实体/AI tick，
     *  避免指令在高负载（实体多/地形慢）时被饿死到 level.tick 结束才执行（仅全局开启时）。 */
    @Inject(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At("HEAD"))
    private void modzuozhi_drainPlayerPackets(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        if (ModZuozhi.isEnabled()) {
            this.modzuozhi_playerPacketQueue.drain();
        }
    }
}
