package dev.modzuozhi.core.dimthread;

import net.minecraft.core.BlockPos;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 按区块坐标的细粒度可重入锁（借鉴 MCMT 的 ChunkLock 思路，写我们自己的实现）。
 * <p>
 * 细粒度并行（subtask）下，多个子任务可能并发访问<b>同一区块</b>内的方块实体 / 方块 /
 * getChunk / 光照等结构。用「区块坐标 → {@link ReentrantLock}」做互斥：同一区块内的子任务
 * 串行，不同区块并行，既保证安全又保留并行度。
 * <p>
 * <b>第5步（6b）：radius 多区块锁。</b>某些子任务会<b>跨区块</b>访问（如破坏方块、爆炸、
 * 大型实体），单区块锁不足。提供 {@link #lock(BlockPos, int)} / {@link #lock(int, int, int)}，
 * 锁定以目标区块为中心 {@code (1+2r)²} 个区块的正方形范围：先对目标区块坐标<b>排序</b>
 * 再逐个加锁（排序保证多线程以相同顺序取锁，杜绝循环等待死锁），解锁时<b>逆序</b>释放。
 * 仍保留单区块 {@link #lock(BlockPos)} 供普通子任务使用（等价 radius=0）。
 * <p>
 * 线程安全：锁表用 {@link ConcurrentHashMap}，{@code computeIfAbsent} 原子取锁，避免并发创建。
 * 锁按需惰性创建；为控制内存，锁数量与活跃区块数同量级，可接受（如需要可在后续版本加入过期回收）。
 */
public final class ChunkLock {
    private static final ConcurrentHashMap<Long, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private ChunkLock() {
    }

    /** 锁定包含该世界坐标的区块（返回后必须 {@code close()} 释放）。 */
    public static AutoCloseable lock(BlockPos pos) {
        return lock(pos.getX() >> 4, pos.getZ() >> 4);
    }

    /** 锁定指定区块（返回后必须 {@code close()} 释放）。 */
    public static AutoCloseable lock(int chunkX, int chunkZ) {
        long key = ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
        ReentrantLock lock = LOCKS.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        return lock::unlock;
    }

    /**
     * 锁定包含该世界坐标区块及其周围 radius 圈的区块（{@code (1+2r)²} 个）。
     * 排序加锁、逆序释放，防死锁。返回的 {@link ChunkLockHandle} 用后必须 {@code close()}。
     *
     * @param radius 区块半径（0=仅本区块，1=3×3=9 个区块）
     */
    public static ChunkLockHandle lock(BlockPos pos, int radius) {
        return lock(pos.getX() >> 4, pos.getZ() >> 4, radius);
    }

    /**
     * 锁定指定区块及其周围 radius 圈的区块（{@code (1+2r)²} 个）。
     * 排序加锁、逆序释放，防死锁。返回的 {@link ChunkLockHandle} 用后必须 {@code close()}。
     */
    public static ChunkLockHandle lock(int chunkX, int chunkZ, int radius) {
        long[] targets = new long[(1 + radius * 2) * (1 + radius * 2)];
        int idx = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                targets[idx++] = ((long) (chunkX + dx) << 32) ^ ((chunkZ + dz) & 0xFFFFFFFFL);
            }
        }
        // 排序保证所有线程以相同顺序取锁，避免循环等待死锁
        Arrays.sort(targets);
        for (long key : targets) {
            LOCKS.computeIfAbsent(key, k -> new ReentrantLock()).lock();
        }
        return new ChunkLockHandle(targets);
    }

    /**
     * 多区块锁的句柄：持有已加锁的区块 key 数组，{@link #close()} 逆序释放。
     */
    public static final class ChunkLockHandle implements AutoCloseable {
        private final long[] targets;

        private ChunkLockHandle(long[] targets) {
            this.targets = targets;
        }

        @Override
        public void close() {
            // 逆序释放（与加锁方向相反）
            for (int i = targets.length - 1; i >= 0; i--) {
                ReentrantLock lock = LOCKS.get(targets[i]);
                if (lock != null) {
                    lock.unlock();
                }
            }
        }
    }
}
