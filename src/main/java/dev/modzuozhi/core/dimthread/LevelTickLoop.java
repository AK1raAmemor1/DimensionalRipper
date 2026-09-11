package dev.modzuozhi.core.dimthread;

import com.mojang.logging.LogUtils;
import dev.modzuozhi.ModZuozhi;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.neoforged.neoforge.event.EventHooks;
import org.slf4j.Logger;

import java.util.Iterator;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * 维度独立 tick 循环（借鉴 Chlorophyll 的 {LevelTickLoop 思路，用我们自己的
 * {@link DimThreadCore#swapThreadsAndRun} 机制独立重写）。
 * <p>
 * <b>目标</b>：每个 {@link ServerLevel} 一个常驻循环，各自按 tick 预算独立排下一次 tick。
 * 某个维度 tick 慢（低 TPS）只会推迟它自己的下一次 tick，不再像旧「有界屏障」模型那样由
 * 主线程 {@code awaitCompletion()} 拖累其它维度，从而实现维度隔离。
 * <p>
 * 每个循环在一"拍"里打包三件事（对应原版 {@code tickChildren} 里逐维度的部分）：
 * <ol>
 *     <li>本维度 {@code level.tick()}；</li>
 *     <li>本维度玩家的连接 tick（原版玩家 {@code doTick()} 走的就是网络连接路径）；</li>
 *     <li>本维度玩家的区块发送 + 刷新恢复。</li>
 * </ol>
 * 全局部分（命令函数、全局连接监听、playerList 延迟广播、自动保存）仍留在主线程，
 * 见 {@code MinecraftServerDimMixin} 的后续改造。
 */
public final class LevelTickLoop implements Runnable, java.util.concurrent.Executor {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final ServerLevel level;
    private final ScheduledThreadPoolExecutor masterPool;

    /** 当前维度的玩家连接集合（玩家登录/断开时增删，见连接路由改造）。 */
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();

    /** 从主线程/跨线程投递回本 loop 执行的任务队列。 */
    private final Queue<Runnable> taskScope = new ConcurrentLinkedQueue<>();

    /** 本 loop 正在调度中的下一次 tick 任务。 */
    private volatile Future<?> currentTickTask;
    /** 是否还在运行（false 表示已申请停止）。 */
    private volatile boolean running = true;
    /** 是否已启动过自排程（防止重复 schedule 出多个并列循环）。 */
    private volatile boolean scheduled = false;
    /** 是否正在执行一次 tick（health 检查用）。 */
    private volatile boolean ticking = false;
    /** 单机内嵌服务器暂停（ESC 菜单）时置 true：取消下一拍并不再自排，杜绝暂停/保存期间并行写区块。 */
    private volatile boolean paused = false;

    /** 本循环独立的 tick 计数（用于每 20 tick 的时间同步）。 */
    private int tickCount;

    public LevelTickLoop(ServerLevel level, ScheduledThreadPoolExecutor masterPool) {
        this.level = level;
        this.masterPool = masterPool;
    }

    public ServerLevel getLevel() {
        return this.level;
    }

    public boolean isTicking() {
        return this.ticking;
    }

    public boolean isRunning() {
        return this.running;
    }

    /** 是否已被主线程 {@code tickChildren} 启动过（即该 loop 已接管本维度 tick）。 */
    public boolean isScheduled() {
        return this.scheduled;
    }

    /** 申请停止自排程（维度卸载 / 服务器停机时调用）。 */
    public void kill() {
        this.running = false;
        Future<?> task = this.currentTickTask;
        if (task != null && !task.isCancelled()) {
            task.cancel(true);
        }
    }

    /** 首次启动（或外部主动重排）本循环。 */
    public void schedule() {
        try {
            this.currentTickTask = this.masterPool.submit(this);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // masterPool 已被关闭（停机/降级/重启用）：放弃排程，run 内的 running 检查兜底
        }
    }

    /** 每 tick 由主线程调用：保证本 loop 只启动一次自排程。 */
    public void scheduleIfNeeded() {
        if (!this.scheduled) {
            this.scheduled = true;
            this.schedule();
        }
    }

    public boolean isPaused() {
        return this.paused;
    }

    /**
     * 暂停本维度循环（单机暂停场景调用）：置暂停标志并取消尚未开始的下一拍。
     * <p>
     * 正在执行的当前拍会自然跑完（其 finally 检查暂停后不再续排），随后维度 worker 完全停摆，
     * 不再有任何并行写入 LevelChunk 的随机tick/TE/实体，消灭「暂停/保存期间区块数据包序列化
     * 读到中间态 → 客户端解码越界 → 网络协议错误掉线」的竞态。
     */
    public void pause() {
        if (this.paused) {
            return;
        }
        this.paused = true;
        Future<?> task = this.currentTickTask;
        if (task != null && !task.isCancelled()) {
            task.cancel(false);
        }
    }

    /** 恢复本维度循环：立即重排下一拍。 */
    public void resume() {
        if (!this.paused) {
            return;
        }
        this.paused = false;
        this.schedule();
    }

    public void addConnection(Connection connection) {
        this.connections.add(connection);
    }

    public void removeConnection(Connection connection) {
        this.connections.remove(connection);
    }

    @Override
    public void execute(java.lang.Runnable command) {
        this.taskScope.offer(command);
    }

    @Override
    public void run() {
        DimThreadCore.attach(Thread.currentThread(), this.level);
        // 暂停期间若仍有取消失败/竞态残留的任务被执行，直接放弃本拍（不进入维度 tick，不续排）；
        // 正常路径下 pause() 会 cancel 掉当前拍，这里只是兜底。
        if (this.paused || !this.running) {
            this.currentTickTask = null;
            return;
        }
        this.ticking = true;

        MinecraftServer server = this.level.getServer();
        long nspt = server.tickRateManager().nanosecondsPerTick();
        if (server.tickRateManager().isSprinting()) {
            nspt = 0L;
        }

        long tickStart = System.nanoTime();
        long deadline = tickStart + nspt;
        try {
            this.processPendingTasks();
            DimThreadCore.swapThreadsAndRun(() -> {
                long workStart = System.nanoTime();
                this.internalTick();
                // 只统计真实 tick 工作量（MSPT），不含后面 drainChunkTasks 的按预算轮询等待，
                // 避免把定时器唤醒延迟等「非负载时间」算进 TPS（否则空闲也会显示 19.7）。
                TpsTracker.recordDimension(this.level.dimension(), System.nanoTime() - workStart);
                this.drainChunkTasks(deadline);
            }, this.level, this.level.getChunkSource());
        } catch (Throwable t) {
            ModZuozhi.LOGGER.error("[DimThread] 维度 {} tick 异常", this.level.dimension().location(), t);
            FaultGuard.onDimTickFailure(t);
        } finally {
            this.ticking = false;
            if (this.running && !this.paused) {
                long remaining = Math.max(0L, deadline - System.nanoTime());
                try {
                    this.currentTickTask = this.masterPool.schedule(this, remaining, TimeUnit.NANOSECONDS);
                } catch (java.util.concurrent.RejectedExecutionException ignored) {
                    // masterPool 已关闭：放弃续排
                }
            } else if (!this.running) {
                ModZuozhi.LOGGER.info("[DimThread] 维度 {} 独立循环已停止", this.level.dimension().location());
            }
        }
    }

    /** 执行由 {@link #execute} 投递回来的主线程任务。 */
    private void processPendingTasks() {
        Runnable task;
        while ((task = this.taskScope.poll()) != null) {
            try {
                task.run();
            } catch (Throwable t) {
                ModZuozhi.LOGGER.error("[DimThread] 维度 {} 主线程任务异常", this.level.dimension().location(), t);
            }
        }
    }

    /**
     * 排空本维度区块源任务（距离更新 + 状态提升 + 生成调度 + 光照调度 + 队列）。
     * <p>
     * 原版主线程在 {@code waitUntilNextTick → pollTaskInternal → ServerChunkCache.pollTask} 期间会
     * <b>连续</b>排空这些任务，是区块加载吞吐量的主要来源。切到维度 loop 后主线程那份被
     * {@code ServerChunkCachePollMixin} 跳过，改由本 worker 独占；这里在本 tick 的剩余预算内等价地
     * 连续排空，直到无更多任务或预算耗尽，避免地形加载掉速、卡在未加载区块。
     */
    private void drainChunkTasks(long deadline) {
        ServerChunkCache chunkSource = this.level.getChunkSource();
        long now = System.nanoTime();
        // 低 TPS 时 internalTick 可能已超预算（deadline 已过），若直接退出会与区块加载形成「死亡螺旋」：
        // 区块不加载 → 实体/光照等待区块 → tick 更慢 → 更没有预算排空区块。因此给一个最低保证窗口。
        long effectiveDeadline = Math.max(deadline, now + 2_000_000L); // 最低 2ms
        boolean overBudget = now >= deadline;
        while (this.running && System.nanoTime() < effectiveDeadline) {
            if (chunkSource.pollTask()) {
                continue; // 有就绪任务，继续排空
            }
            // 区块任务空档期补排空一次玩家包（移动/交互等非命令包），减轻其在高负载下的延迟。
            ((IPlayerPacketQueue) this.level).modzuozhi$playerPacketQueue().drain();
            if (overBudget) {
                break; // 超预算窗口内不可忙等，避免继续占用 worker
            }
            // 正常预算内：区块生成是异步的，几毫秒内可能有后台任务完成回调；极短退避持续轮询，
            // 避免错过异步结果导致管线饥饿（等价原版主线程 managedBlock）。
            LockSupport.parkNanos(100_000L); // 0.1ms
        }
    }

    /**
     * 一"拍"的维度内部 tick：排空玩家包（命令）→ 挂起刷新 → 时间同步 → level.tick →
     * 连接 tick（玩家 doTick）→ 区块发送 + 恢复刷新。注意：本阶段暂不把自动保存下沉到 loop，
     * 仍由主线程全局保存，避免与 {@code MinecraftServerSaveMixin} 双保存。
     */
    private void internalTick() {
        // 先排空玩家包队列（命令等），再进沉重的 level.tick：实体/AI 卡顿时命令仍能及时执行，
        // 不会像原来那样被饿死到 level.tick 结束（RETURN）才处理。
        ((IPlayerPacketQueue) this.level).modzuozhi$playerPacketQueue().drain();

        MinecraftServer server = this.level.getServer();
        this.tickCount++;

        for (Connection connection : this.connections) {
            ((ServerGamePacketListenerImpl) connection.getPacketListener()).suspendFlushing();
        }

        if (this.tickCount % 20 == 0) {
            ((dev.modzuozhi.mixin.MinecraftServerTimeSyncAccessor) server).modzuozhi$invokeSynchronizeTime(this.level);
        }

        EventHooks.fireLevelTickPre(this.level, () -> true);
        try {
            this.level.tick(() -> true);
        } catch (Throwable throwable) {
            ModZuozhi.LOGGER.error("[DimThread] 维度 {} tick 抛异常", this.level.dimension().location(), throwable);
        }
        EventHooks.fireLevelTickPost(this.level, () -> true);

        this.tickConnections();

        for (Connection connection : this.connections) {
            ServerGamePacketListenerImpl handler = (ServerGamePacketListenerImpl) connection.getPacketListener();
            handler.chunkSender.sendNextChunks(handler.player);
            handler.resumeFlushing();
        }
    }

    /** tick 本维度玩家的连接（正是这里触发原版 {@code ServerPlayer.doTick()}）。 */
    private void tickConnections() {
        Iterator<Connection> iterator = this.connections.iterator();
        while (iterator.hasNext()) {
            Connection connection = iterator.next();
            if (connection.isConnected()) {
                try {
                    connection.tick();
                } catch (Exception e) {
                    // 区块未就绪类瞬时异常（玩家 tick 请求尚未加载的位置区块，常见于首次传送进
                    // 全新维度、玩家位置 ticket 尚未登记）：跳过本拍，下一 tick 区块就绪后自愈，
                    // 不执行断线（原行为会把玩家踢成 "Internal server error"）。
                    if (isChunkNotReady(e)) {
                        ModZuozhi.LOGGER.warn("维度 {} 玩家 tick 跳过（区块未就绪）：{}",
                                this.level.dimension().location(), e.getMessage());
                        continue;
                    }
                    ModZuozhi.LOGGER.warn("维度 {} 玩家连接 tick 失败 {}",
                            this.level.dimension().location(), connection.getLoggableAddress(serverTelemetry()), e);
                    Component message = Component.literal("Internal server error");
                    connection.send(new ClientboundDisconnectPacket(message),
                            PacketSendListener.thenRun(() -> connection.disconnect(message)));
                    connection.setReadOnly();
                }
            } else {
                iterator.remove();
                connection.handleDisconnection();
            }
        }
    }

    /** 是否为"区块未就绪"（Chunk not there when requested）瞬时异常（遍历 cause 链）。 */
    private static boolean isChunkNotReady(Throwable t) {
        while (t != null) {
            if (t instanceof IllegalStateException
                    && t.getMessage() != null
                    && t.getMessage().contains("Chunk not there when requested")) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    private boolean serverTelemetry() {
        return this.level.getServer().logIPs();
    }
}