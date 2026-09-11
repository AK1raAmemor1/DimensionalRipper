package dev.modzuozhi.core.dimthread;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * 分片 Claim 门（借鉴 Tessellate 的"每区域一个 inFlight CAS claim、慢区掉拍"思想独立实现）。
 * <p>
 * 一片在同一时刻至多允许一个任务运行，claim 状态跨 tick 存续：本 tick 提交的任务在
 * {@link #tryAcquire} 之后、任务完成 {@link #release} 之前，下 tick 对该片的抢占必然失败 →
 * 整片掉拍延后（等价"该片本 tick 视为空"，与 MC 实体密度限制同性质，无感知）。
 * <p>
 * 收益：阶段收口从"等全部子任务"（慢片拖累全体）变成"只等已提交且上一 tick 已完成过的片"，
 * 慢片不再阻塞维度 worker。
 * <p>
 * 线程安全：仅使用 CAS 置位，无锁、无等待。
 */
public final class ShardGate {

    /** 分片数：2 的幂（供 & 掩码），最低 8，按 CPU 核数线性扩展（≈ 2×核数）。 */
    public static final int SHARDS = Integer.highestOneBit(Math.max(8, Runtime.getRuntime().availableProcessors() * 2));

    /**
     * 一 tick 的预算（纳秒，50ms = 20 TPS 的单 tick 时长）。
     * <p>
     * 观测型掉拍阈值：分片任务实际耗时超过该值即视为"该片本 tick 掉拍"——它没能在
     * 一拍时间内处理完本片负载（慢片）。由各阶段分片任务完成后的耗时统计计入累计
     * （claim 抢占失败路径因阶段收口会在本 tick 内等齐所有分片，结构上不可达）。
     */
    public static final long TICK_BUDGET_NS = 50_000_000L;

    /** 黄金比例乘散列常数（Fibonacci hashing），保证相邻坐标映射到不同分片。 */
    private static final long GOLDEN = 0x9E3779B97F4A7C15L;

    // ===== 各阶段 per-dimension 门表 =====
    //
    // 实体 / 方块实体 / 区块环境是维度 tick 内三个独立阶段，负载特征互不相同，
    // 必须各自独立 claim（共用同一门表会让某阶段慢片拖累其它阶段的同片）。
    // 实体阶段的门表在 EntityTickParallel.GATES（历史实现，保持不动）。

    /** 方块实体（TE）分片门表：按维度隔离。 */
    private static final ConcurrentHashMap<ResourceKey<?>, ShardGate> TE_GATES = new ConcurrentHashMap<>();

    /** 区块环境（随机 tick / 流体）分片门表：按维度隔离。 */
    private static final ConcurrentHashMap<ResourceKey<?>, ShardGate> CHUNK_GATES = new ConcurrentHashMap<>();

    /** 累计 TE 掉拍分片数（诊断 / status 展示；观测型，超 {@link #TICK_BUDGET_NS} 计入）。 */
    private static final LongAdder TE_MISSED = new LongAdder();

    /** 累计区块环境掉拍分片数（诊断 / status 展示；观测型，超 {@link #TICK_BUDGET_NS} 计入）。 */
    private static final LongAdder CHUNK_MISSED = new LongAdder();

    /** 每片一个 claim 位（false=空闲，true=本 tick 有任务在跑/已提交）。 */
    private final AtomicBoolean[] gates = new AtomicBoolean[SHARDS];

    public ShardGate() {
        for (int i = 0; i < SHARDS; i++) {
            gates[i] = new AtomicBoolean(false);
        }
    }

    /**
     * CAS false→true 抢占本片。
     *
     * @return true=允许提交（本片空闲，已占用）；false=上一 tick 该片任务尚未完成（掉拍）。
     */
    public boolean tryAcquire(int shard) {
        return gates[shard].compareAndSet(false, true);
    }

    /** 本片任务完成时释放 claim，允许下 tick 重新提交。 */
    public void release(int shard) {
        gates[shard].set(false);
    }

    // ===== 静态工厂与统计 =====

    /** 取本维度的 TE 分片门（缺失即建；维度数量极少，长期缓存可接受）。 */
    public static ShardGate teGate(ResourceKey<Level> dimension) {
        return TE_GATES.computeIfAbsent(dimension, key -> new ShardGate());
    }

    /** 取本维度的区块环境分片门（缺失即建；维度数量极少，长期缓存可接受）。 */
    public static ShardGate chunkGate(ResourceKey<Level> dimension) {
        return CHUNK_GATES.computeIfAbsent(dimension, key -> new ShardGate());
    }

    /** 按方块坐标稳定散列到分片索引（TE 分片用；黄金比例乘散列取高位）。 */
    public static int shardOf(BlockPos pos) {
        long hash = GOLDEN;
        hash = (hash ^ pos.getX()) * GOLDEN;
        hash = (hash ^ pos.getY()) * GOLDEN;
        hash = (hash ^ pos.getZ()) * GOLDEN;
        return (int) (hash >>> 32) & (SHARDS - 1);
    }

    /** 按 {@code ChunkPos.asLong(x,z)} 稳定散列到分片索引（区块环境分片用）。 */
    public static int shardOf(long chunkPos) {
        long hash = chunkPos * GOLDEN;
        return (int) (hash >>> 32) & (SHARDS - 1);
    }

    /** TE 掉拍分片累计（诊断 / status 展示）。 */
    public static long teMissed() {
        return TE_MISSED.sum();
    }

    /** 区块环境掉拍分片累计（诊断 / status 展示）。 */
    public static long chunkMissed() {
        return CHUNK_MISSED.sum();
    }

    /** TE 分片掉拍计数（观测型：分片任务耗时超预算、或 claim 抢占失败）。 */
    public static void bumpTeMissed() {
        TE_MISSED.increment();
    }

    /** 区块环境分片掉拍计数（观测型：分片任务耗时超预算、或 claim 抢占失败）。 */
    public static void bumpChunkMissed() {
        CHUNK_MISSED.increment();
    }
}