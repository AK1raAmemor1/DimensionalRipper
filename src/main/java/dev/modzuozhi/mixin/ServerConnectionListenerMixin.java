package dev.modzuozhi.mixin;

import com.mojang.logging.LogUtils;
import dev.modzuozhi.core.dimthread.DimThreadCore;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConnectionListener;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Iterator;
import java.util.List;

/**
 * 维度循环模型：主线程的连接监听不再 tick 游戏连接（防止玩家被双 tick）。
 * <p>
 * 原版 {@code ServerConnectionListener.tick()} 会 tick 所有连接（游戏连接触发
 * {@code ServerPlayer.doTick()}）。维度 loop 的 {@code tickConnections()} 已经 tick 了它
 * 名下的游戏连接，因此这里对 {@link ServerGamePacketListenerImpl}（游戏连接）只做「断开时
 * 从监听列表移除」，不 tick、不重复清理（断开清理由 loop 完成）。登录/状态/配置连接仍在本处 tick。
 */
@Mixin(ServerConnectionListener.class)
public abstract class ServerConnectionListenerMixin {

    @Shadow
    @Final
    private List<Connection> connections;

    @Shadow
    @Final
    private MinecraftServer server;

    /**
     * @reason 游戏连接交维度 loop tick；主线程只兜底登录/状态/配置连接与列表清理。
     * 全局开关关闭（isActive=false）时恢复原版：所有连接（含游戏连接）都回主线程 tick。
     */
    @Overwrite
    public void tick() {
        boolean active = DimThreadCore.MANAGER.isActive(this.server);
        synchronized (this.connections) {
            Iterator<Connection> iterator = this.connections.iterator();
            while (iterator.hasNext()) {
                Connection connection = iterator.next();
                if (connection.isConnecting()) {
                    continue;
                }
                boolean game = connection.getPacketListener() instanceof ServerGamePacketListenerImpl;
                if (connection.isConnected()) {
                    if (game && active) {
                        continue; // 由所属维度 loop tick
                    }
                    try {
                        connection.tick();
                    } catch (Exception e) {
                        Logger logger = LogUtils.getLogger();
                        logger.warn("Failed to handle packet for {}",
                                connection.getLoggableAddress(this.server.logIPs()), e);
                        Component component = Component.literal("Internal server error");
                        connection.send(new ClientboundDisconnectPacket(component),
                                PacketSendListener.thenRun(() -> connection.disconnect(component)));
                        connection.setReadOnly();
                    }
                } else {
                    iterator.remove();
                    if (!game || !active) {
                        connection.handleDisconnection();
                    }
                }
            }
        }
    }
}