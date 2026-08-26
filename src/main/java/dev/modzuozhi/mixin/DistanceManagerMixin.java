package dev.modzuozhi.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.Ticket;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;

import java.util.concurrent.locks.ReentrantLock;

/**
 * {@code DistanceManager}（区块 ticket 调度）并发保护。
 * <p>
 * 维度并行下，维度 worker / 细粒度 subtask 的 {@code getChunk} 会在同步路径里调用
 * {@code addTicket/removeTicket} 修改 ticket，而主线程 executor 里的
 * {@code PlayerTicketTracker.onLevelChange}（玩家移动/跨维度传送触发）也在修改同一
 * {@code DistanceManager}，两者并发导致内部 {@code SortedArraySet.first()} 读到空集等
 * NPE 竞态。
 * <p>
 * 这里的锁覆盖所有 ticket 写操作，使「维度侧 getChunk 的 ticket 修改」与「主线程玩家的
 * ticket 变更」互斥，从根上消除 DistanceManager 并发竞态。ticket 操作不频繁，串行化代价低。
 */
@Mixin(DistanceManager.class)
public class DistanceManagerMixin {
    private static final ReentrantLock LOCK = new ReentrantLock();

    @WrapMethod(method = "addTicket")
    private void modzuozhi_lockAddTicket(long chunkPos, Ticket<?> ticket, Operation<Void> original) {
        LOCK.lock();
        try {
            original.call(chunkPos, ticket);
        } finally {
            LOCK.unlock();
        }
    }

    @WrapMethod(method = "removeTicket")
    private void modzuozhi_lockRemoveTicket(long chunkPos, Ticket<?> ticket, Operation<Void> original) {
        LOCK.lock();
        try {
            original.call(chunkPos, ticket);
        } finally {
            LOCK.unlock();
        }
    }

    @WrapMethod(method = "updateChunkForced")
    private void modzuozhi_lockUpdateChunkForced(ChunkPos pos, boolean add, Operation<Void> original) {
        LOCK.lock();
        try {
            original.call(pos, add);
        } finally {
            LOCK.unlock();
        }
    }

    @WrapMethod(method = "removeTicketsOnClosing")
    private void modzuozhi_lockRemoveTicketsOnClosing(Operation<Void> original) {
        LOCK.lock();
        try {
            original.call();
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * 维度循环模型下，主线程 {@code ServerChunkCache.pollTask} 与维度 worker 的
     * {@code level.tick → ServerChunkCache.tick} 会<b>并发</b>调用 {@code runAllUpdates}，
     * 遍历 {@code chunksToUpdateFutures}（普通 HashSet）时抛 {@code ConcurrentModificationException}。
     * 用同一把锁串行化，彻底消除该竞态。
     */
    @WrapMethod(method = "runAllUpdates")
    private boolean modzuozhi_lockRunAllUpdates(ChunkMap chunkMap, Operation<Boolean> original) {
        LOCK.lock();
        try {
            return original.call(chunkMap);
        } finally {
            LOCK.unlock();
        }
    }

    /** {@code purgeStaleTickets} 无锁遍历 + 修改 {@code tickets}，与主线程 ticket 写并发时需要串行化。 */
    @WrapMethod(method = "purgeStaleTickets")
    private void modzuozhi_lockPurgeStaleTickets(Operation<Void> original) {
        LOCK.lock();
        try {
            original.call();
        } finally {
            LOCK.unlock();
        }
    }

    /** 玩家登录/跨维度在 worker 或主线程改 {@code playersPerChunk}，与 runAllUpdates 并发时串行化。 */
    @WrapMethod(method = "addPlayer")
    private void modzuozhi_lockAddPlayer(SectionPos pos, ServerPlayer player, Operation<Void> original) {
        LOCK.lock();
        try {
            original.call(pos, player);
        } finally {
            LOCK.unlock();
        }
    }

    @WrapMethod(method = "removePlayer")
    private void modzuozhi_lockRemovePlayer(SectionPos pos, ServerPlayer player, Operation<Void> original) {
        LOCK.lock();
        try {
            original.call(pos, player);
        } finally {
            LOCK.unlock();
        }
    }
}
