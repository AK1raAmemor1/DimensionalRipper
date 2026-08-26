package dev.modzuozhi.mixin;

import dev.modzuozhi.ModZuozhi;
import dev.modzuozhi.core.dimthread.IPlayerPacketQueue;
import dev.modzuozhi.core.dimthread.PlayerPacketQueue;
import net.minecraft.ReportedException;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.server.RunningOnDifferentThreadException;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.thread.BlockableEventLoop;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 第2步：网络包按玩家分派到维度线程——包处理入口重定向（1.21.1 API 适配版）。
 * <p>
 * 1.21.1 的 {@code PacketUtils.ensureRunningOnSameThread} 有 3 参数重载：
 * {@code (Packet, T, ServerLevel)} 和 {@code (Packet, T, BlockableEventLoop)}。
 * <b>真实包链路</b>（1.21.1）：Netty IO 线程 {@code Connection.channelRead0 → genericsFtw →
 * packet.handle(listener)} 直接调用 handle；而 {@code ServerGamePacketListenerImpl} 几乎
 * 每个 {@code handleXxx} 方法开头都会调 {@code ensureRunningOnSameThread(ServerLevel)}，
 * 非主线程时投递到主线程队列并抛 {@code RunningOnDifferentThreadException}（由
 * {@code genericsFtw} 吞掉），主线程队列执行时才真正跑 handle 逻辑。
 * <p>
 * 借鉴 Chlorophyll（Unlicense 1.21.10 Fabric）的 {@code PacketUtilsMixin} 思路，但适配
 * 1.21.1 NeoForge 签名：全局开启且是游戏包时，把 handle 任务投递到<b>该玩家所在维度</b>的
 * {@code PlayerPacketQueue}（替代投主线程队列），由维度 worker 在 tick 收口处执行。
 * 登录/配置/状态包仍走原版 {@code BlockableEventLoop} 重载。
 * <p>
 * <b>防无限循环（必须）</b>：包投到维度队列后，worker drain 执行 {@code packet.handle}，
 * handle 开头会再次调本方法。因此用 {@link PlayerPacketQueue#isExecuting()} 标记（drain 中
 * 设置）区分两种状态：
 * <ul>
 *     <li>Netty 线程首次调度（未在展开中、非主线程）→ 投玩家维度队列 + 抛异常；</li>
 *     <li>维度 worker / 主线程 drain 中执行 handle（{@code isExecuting()} 或主线程）→
 *         <b>直接返回</b>，让 handle 继续执行，避免包被无限投回队列。</li>
 * </ul>
 * <p>
 * 死锁评估：{@code ServerGamePacketListenerImpl} 无 {@code executeBlocking}；唯一的
 * {@code ServerCommonPacketListenerImpl.disconnect} 的 {@code executeBlocking} 已由
 * {@code ServerCommonPacketListenerDimMixin} 异步化，worker 不阻塞。
 */
@Mixin(PacketUtils.class)
public abstract class PacketUtilsDimMixin {

    @Shadow
    @Final
    private static Logger LOGGER;

    @Shadow
    public static <T extends PacketListener> ReportedException makeReportedException(Exception exception, Packet<T> packet, T packetListener) {
        throw new AssertionError();
    }

    /**
     * @reason 把游戏包处理按玩家分派到其所在维度线程（第2步）
     */
    @Overwrite
    public static <T extends PacketListener> void ensureRunningOnSameThread(
            Packet<T> packet, T packetListener, ServerLevel serverLevel
    ) throws RunningOnDifferentThreadException {
        if (packetListener instanceof ServerGamePacketListenerImpl && ModZuozhi.isEnabled()) {
            // 命令/聊天包改走原版主线程（BlockableEventLoop）：
            // 维度 loop 一旦被实体/区块 tick 阻塞，命令会跟着延迟 1-2 分钟才执行；而主线程只做
            // 协调（不 tick 实体），能保持约 50ms 一拍，命令可秒响应。聊天广播是全局的，卡顿维度
            // 的聊天包被拖住会连累其它维度的玩家延迟收到消息，故聊天/命令都走主线程。
            boolean routeMainThread = packet instanceof ServerboundChatCommandPacket
                    || packet instanceof ServerboundChatCommandSignedPacket
                    || packet instanceof ServerboundChatPacket;
            if (!routeMainThread) {
                // 两种情况不拦截，直接让 handle 继续执行（等价原版"已在正确线程"）：
                //   1. PlayerPacketQueue.drain() 正在执行包任务（维度 worker / 主线程串行）
                //   2. 当前已在主线程（原版主线程队列执行路径）
                if (PlayerPacketQueue.isExecuting() || serverLevel.getServer().isSameThread()) {
                    return;
                }
                // 首次调度（Netty IO 线程）：投递到玩家所在维度的队列，由该维度线程在 tick 收口执行
                ServerLevel playerLevel = (ServerLevel) ((ServerGamePacketListenerImpl) packetListener).player.level();
                ((IPlayerPacketQueue) playerLevel).modzuozhi$playerPacketQueue().offer(() -> {
                    if (packetListener.shouldHandleMessage(packet)) {
                        try {
                            packet.handle(packetListener);
                        } catch (Exception e) {
                            if (e instanceof ReportedException reportedException
                                    && reportedException.getCause() instanceof OutOfMemoryError) {
                                throw makeReportedException(e, packet, packetListener);
                            }
                            packetListener.onPacketError(packet, e);
                        }
                    } else {
                        LOGGER.debug("Ignoring packet due to disconnection: {}", packet);
                    }
                });
                throw RunningOnDifferentThreadException.RUNNING_ON_DIFFERENT_THREAD;
            }
            // routeMainThread == true：落到下方走原版 BlockableEventLoop（主线程）路径
        }

        // 登录/配置/状态包，或全局关闭：走原版 BlockableEventLoop 重载
        PacketUtils.ensureRunningOnSameThread(packet, packetListener, (BlockableEventLoop<?>) serverLevel.getServer());
    }
}
