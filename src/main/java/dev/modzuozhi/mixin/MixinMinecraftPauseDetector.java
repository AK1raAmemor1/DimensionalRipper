package dev.modzuozhi.mixin;

import dev.modzuozhi.core.dimthread.DimThreadCore;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 客户端侧：每帧检测单机内嵌服务器的暂停状态（ESC 菜单「暂停并保存」）。
 * <p>
 * 修复历史 bug：单机暂停只冻结主线程，Dimensional Ripper 的维度 worker 不受影响仍在并行
 * 写入 {@code LevelChunk}，与主线程的存盘/区块数据包序列化并发 → 客户端收到残缺区块包解码
 * 越界（"网络协议错误"）掉线（ESC 暂停后 0.5s 内必现）。这里在暂停开始的帧内把维度 loop
 * 一并冻结（见 {@link DimThreadCore#setPaused(boolean)}），恢复时自动重排。
 * <ul>
 *     <li>LAN 开放时 {@code Minecraft.isPaused()} 恒为 false，不会误暂停；</li>
 *     <li>dedicated server 无客户端，本 mixin 仅注册于 {@code client} 段，不会被加载；</li>
 *     <li>{@code setPaused} 内部同值短路，每帧一次布尔比较开销可忽略。</li>
 * </ul>
 */
@Mixin(Minecraft.class)
public abstract class MixinMinecraftPauseDetector {

    @Inject(method = "runTick", at = @At("HEAD"))
    private void modzuozhi$syncPauseState(CallbackInfo ci) {
        DimThreadCore.setPaused(((Minecraft) (Object) this).isPaused());
    }
}