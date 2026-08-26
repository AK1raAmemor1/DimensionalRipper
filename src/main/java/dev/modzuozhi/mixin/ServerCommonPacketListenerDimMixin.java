package dev.modzuozhi.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.modzuozhi.ModZuozhi;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 第2步：网络包按玩家分派到维度线程——断开流程死锁防护。
 * <p>
 * 原版 {@code ServerCommonPacketListenerImpl.disconnect} 用
 * {@code MinecraftServer.executeBlocking} 把清理任务提交到主线程并<b>阻塞等待</b>。当包处理
 * 已移到维度线程（worker 在 {@code ServerLevel.tick} 收口执行包）时，主线程正阻塞在
 * {@code awaitCompletion} 等待维度 worker 完成——worker 再 executeBlocking 等主线程会
 * <b>互相等待死锁</b>。因此全局开启且为游戏连接时，把断开清理改为<b>异步</b>投递主线程
 * （{@code execute}），不阻塞 worker，杜绝死锁。
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerDimMixin {

    @WrapOperation(method = "disconnect(Lnet/minecraft/network/DisconnectionDetails;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/MinecraftServer;executeBlocking(Ljava/lang/Runnable;)V"))
    private void modzuozhi_asyncDisconnect(MinecraftServer server, Runnable task, Operation<Void> original) {
        if (ModZuozhi.isEnabled() && ((Object) this) instanceof ServerGamePacketListenerImpl) {
            // 异步投主线程，worker 不阻塞 → 防与主线程 awaitCompletion 互相等待死锁
            server.execute(task);
        } else {
            original.call(server, task);
        }
    }
}
