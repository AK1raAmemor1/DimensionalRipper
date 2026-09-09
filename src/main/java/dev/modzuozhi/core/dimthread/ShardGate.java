package dev.modzuozhi.core.dimthread;

import java.util.concurrent.atomic.AtomicBoolean;

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

    /**
     * 分片数：2 的幂（供 & 掩码），最低 8，按 CPU 核数线性扩展（≈ 2×核数）。
     */
    public static final int SHARDS = Integer.highestOneBit(Math.max(8, Runtime.getRuntime().availableProcessors() * 2));

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
}