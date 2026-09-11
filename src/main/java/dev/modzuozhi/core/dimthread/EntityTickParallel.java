package dev.modzuozhi.core.dimthread;

import dev.modzuozhi.ModZuozhi;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

/**
 * 实体 tick 细粒度并行的上下文管理（借鉴 MCMT/Async 思路，写我们自己的实现）。
 * <p>
 * 实体 tick 并行与方块实体/区块环境并行的关键区别：{@code ServerLevel.tick} 里对每个实体
 * 的 {@code guardEntityTick(...)} 调用位于 {@code entityTickList.forEach(...)} 的 lambda 内部，
 * 无法像 TE/区块那样在循环体内 {@code @Redirect}。因此这里用 <b>ThreadLocal 屏障</b>在
 * {@code ServerLevelEntityTickMixin} 的 {@code forEach} 前后建立/收口，再由
 * {@code EntityTickParallelMixin} 在 {@code guardEntityTick} 处读取当前屏障并提交并行。
 * <p>
 * <b>第14步（对齐 Async 零锁路线）</b>：实体移动、生成、移除的共享状态（{@code EntityLookup}、
 * {@code knownUuids}、{@code chunkVisibility}、section 存储）已全部并发化，不再需要全局锁
 * {@link #SECTION_LOCK} 保护。实体生命周期写入现在无锁并行，碰撞查询也走并行。
 * 仅保留 {@code EntitySection} 内容（{@code ClassInstanceMultiMap}）的分片读写锁保护内容遍历
 * 与增删的互斥（Async 未去掉分片锁，保留为安全边界；分片竞争在 256 片下极低，不是瓶颈）。
 */
public final class EntityTickParallel {
    /** 当前维度 worker 线程正在并行 tick 的实体屏障（仅 worker 线程非 null）。 */
    private static final ThreadLocal<FineGrainScheduler.Barrier> CURRENT = new ThreadLocal<>();

    /** 本 tick 收集的待并行实体批（仅并行窗口 worker 线程非 null）。 */
    private static final ThreadLocal<Batch> BATCH = new ThreadLocal<>();

    /** 分片锁数量（2 的幂）。越大锁竞争越小，内存开销约 256×对象头，可忽略。 */
    private static final int STRIPE_COUNT = 256;
    /** 分片读写锁数组：保护单个 {@code EntitySection} 的内容读写（{@code ClassInstanceMultiMap}）。 */
    private static final ReentrantReadWriteLock[] SECTION_LOCKS = new ReentrantReadWriteLock[STRIPE_COUNT];

    /**
     * 实体分片 Claim 门表：按维度隔离（不同维度的实体循环各自独立 claim，互不掉拍）。
     * <p>
     * 门状态跨 tick 存续：本 tick 提交的某片未完成前，下 tick 对该片 tryAcquire 失败 →
     * 整片掉拍延后（等价"该片本 tick 视为空"）。阶段收口 {@link FineGrainScheduler.Barrier#await()}
     * 只等待"实际提交的片"，慢片不再阻塞维度 worker（借鉴 Tessellate 慢区域掉拍思想）。
     */
    private static final ConcurrentHashMap<ResourceKey<?>, ShardGate> GATES = new ConcurrentHashMap<>();

    /**
     * 累计掉拍分片数（诊断 / status 展示）。
     * <p>
     * 观测型指标：{@link #modzuozhi_tickShard} 中分片任务实际耗时 &gt; {@link ShardGate#TICK_BUDGET_NS}
     * （一 tick 预算）即计一次"该片本 tick 掉拍"——它没能在一拍时间内处理完本片负载（慢片）。
     * claim 抢占失败路径因阶段收口 {@link FineGrainScheduler.Barrier#await()} 会在本 tick 内等齐
     * 所有已提交分片，结构上不可达，仅保留作为兜底。
     */
    private static final LongAdder MISSED_SHARDS = new LongAdder();

    /** 实体分片并行的独立开关（默认开启；关闭时回退旧的批式 submitBatch 路径）。 */
    private static volatile boolean sharded = true;

    /**
     * 当前线程的锁配对栈：记录每次 lock 调用时<b>是否真正加了锁</b>，unlock 时弹出对应决定。
     * <p>
     * 背景：{@link #isLockDisabled()} 在「细粒度关闭且无残留子任务」时为 true（零锁纯串行）。
     * 但 {@code /dimensionalripper fine on|off} 运行期切换瞬间，该判定会在一次 lock→unlock 之间翻转：
     * lock 时跳过加锁、unlock 时却执行解锁 → 对未持有的锁 unlock，抛
     * {@code IllegalMonitorStateException: attempt to unlock read lock}（实测：大量村民 tick 时
     * 执行 {@code /dimensionalripper fine on} 触发）。用本栈把 lock 时的决定记录下来，unlock 严格按
     * 记录配对（支持嵌套，LIFO），无论 {@code isLockDisabled()} 是否翻转都保持一致。
     */
    private static final ThreadLocal<Deque<Boolean>> LOCK_STACK = ThreadLocal.withInitial(ArrayDeque::new);

    static {
        for (int i = 0; i < STRIPE_COUNT; i++) {
            SECTION_LOCKS[i] = new ReentrantReadWriteLock();
        }
    }

    private EntityTickParallel() {
    }

    /**
     * 将 section key（{@code SectionPos.asLong(x,y,z)}）映射到分片索引。
     * 使用乘散列取高位，保证相邻 section 均匀分布到不同分片。
     */
    private static int stripe(long sectionKey) {
        long hash = sectionKey * 0x9E3779B97F4A7C15L;
        return (int) (hash >>> 32) & (STRIPE_COUNT - 1);
    }

    /** 对指定 section 分片加<b>写</b>锁（内容增删，独占）；记录配对决定。 */
    public static void lockSection(long sectionKey) {
        boolean locked = !isLockDisabled();
        LOCK_STACK.get().push(locked);
        if (locked) {
            SECTION_LOCKS[stripe(sectionKey)].writeLock().lock();
        }
    }

    /** 对指定 section 分片解<b>写</b>锁；严格按 lock 时的配对决定解锁。 */
    public static void unlockSection(long sectionKey) {
        if (popLocked()) {
            SECTION_LOCKS[stripe(sectionKey)].writeLock().unlock();
        }
    }

    /** 对指定 section 分片加<b>读</b>锁（内容遍历/查询，共享）；记录配对决定。 */
    public static void lockSectionRead(long sectionKey) {
        boolean locked = !isLockDisabled();
        LOCK_STACK.get().push(locked);
        if (locked) {
            SECTION_LOCKS[stripe(sectionKey)].readLock().lock();
        }
    }

    /** 对指定 section 分片解<b>读</b>锁；严格按 lock 时的配对决定解锁。 */
    public static void unlockSectionRead(long sectionKey) {
        if (popLocked()) {
            SECTION_LOCKS[stripe(sectionKey)].readLock().unlock();
        }
    }

    /** 弹出本线程最近一次 lock 的配对决定（LIFO）；栈空视为未加锁（防御）。 */
    private static boolean popLocked() {
        Deque<Boolean> stack = LOCK_STACK.get();
        Boolean locked = stack.pollFirst();
        if (stack.isEmpty()) {
            LOCK_STACK.remove();
        }
        return Boolean.TRUE.equals(locked);
    }

    /**
     * 是否应跳过锁（零开销纯串行）。
     * <p>
     * 锁的唯一目的是保护<b>并行子任务</b>对 {@code EntitySection} 的并发增删。而子任务是否
     * 并行由 {@link FineGrainScheduler#isEnabled()} 决定（{@code submitBatch} 关闭时同步串行）。
     * 因此锁的启用必须与细粒度并行开关<b>一致</b>：若这里跟随全局 {@code ModZuozhi.ENABLED}，
     * 会出现"全局 off → 锁被跳过，但细粒度仍开 → 子任务并行增删而主线程无锁遍历"的
     * {@code ConcurrentModificationException} 竞态（已实测崩溃）。
     * <p>
     * <b>残留保护</b>：关闭细粒度（{@code /dimensionalripper fine off}、{@code /dimensionalripper off}、
     * FaultGuard 降级）瞬间，{@code SUB_POOL} 里可能仍有<b>已提交未跑完</b>的实体子任务；
     * 它们与退化为串行的维度 tick / 主线程并发改 {@code EntitySection}。因此只要还存在残留
     * 子任务（{@link FineGrainScheduler#activeSubTasks()} &gt; 0），锁就必须保持启用，直到
     * {@code awaitIdle} 把它们全部排空后才回到零锁纯串行。
     * <p>
     * 注意：本判定可能随时间变化（{@code fine on|off} 切换）。lock/unlock 的<b>配对一致性</b>
     * 由 {@link #LOCK_STACK} 保证——lock 时快照本判定，unlock 严格按快照执行，不会因判定
     * 在两次调用间翻转而对未持有的锁 unlock。
     */
    public static boolean isLockDisabled() {
        return !FineGrainScheduler.isEnabled() && FineGrainScheduler.activeSubTasks() == 0;
    }

    /** 当前线程的实体屏障（worker 端）；无则 null。 */
    public static FineGrainScheduler.Barrier current() {
        return CURRENT.get();
    }

    /** 实体分片并行开关（默认开启；关闭时回退旧的批式 {@code submitBatch} 路径）。 */
    public static boolean isSharded() {
        return sharded;
    }

    public static void setSharded(boolean value) {
        sharded = value;
        ModZuozhi.LOGGER.info("[EntityTick] 分片并行（实体/TE/区块环境）已切换为 {}", value ? "开启" : "关闭");
    }

    /** 累计掉拍分片数（诊断 / {@code status} 展示；观测型，见 {@link #MISSED_SHARDS}）。 */
    public static long missedShards() {
        return MISSED_SHARDS.sum();
    }

    /**
     * 本 tick 收集的一批待并行实体（同一 consumer，即 {@code tickNonPassenger}）。
     * 收集代替逐实体提交：循环结束后按稳定 id 分片，每片独立提交（分片 Claim 模型），
     * 或按线程池大小切成块提交（非分片回退），把每实体一次的调度/屏障计数开销降到 O(线程数)。
     */
    public static final class Batch {
        public final MinecraftServer server;
        public final Consumer<Entity> consumer;
        public final ResourceKey<Level> dimension;
        /** 按分片存放的实体列表（索引 = entity.getId() & (SHARDS-1)）。 */
        private final List<Entity>[] shards = new List[ShardGate.SHARDS];

        public Batch(MinecraftServer server, Consumer<Entity> consumer, ResourceKey<Level> dimension) {
            this.server = server;
            this.consumer = consumer;
            this.dimension = dimension;
        }

        /** 收集一个实体：按稳定 id 分流到对应分片（id 自增、分布均匀）。 */
        public void add(Entity entity) {
            int shard = entity.getId() & (ShardGate.SHARDS - 1);
            List<Entity> list = this.shards[shard];
            if (list == null) {
                list = new ArrayList<>();
                this.shards[shard] = list;
            }
            list.add(entity);
        }

        /** 全部分片数组（end 遍历用）。 */
        public List<Entity>[] allShards() {
            return this.shards;
        }

        /** 汇总全量实体列表（仅非分片回退路径使用）。 */
        public List<Entity> merged() {
            List<Entity> all = new ArrayList<>();
            for (List<Entity> shard : this.shards) {
                if (shard != null) {
                    all.addAll(shard);
                }
            }
            return all;
        }
    }

    /** 收集一个待并行 tick 的实体（在 {@code guardEntityTick} 中调用，替代逐实体提交）。 */
    public static void collect(MinecraftServer server, Consumer<Entity> consumer, Entity entity) {
        Batch batch = BATCH.get();
        if (batch == null) {
            batch = new Batch(server, consumer, entity.level().dimension());
            BATCH.set(batch);
        }
        batch.add(entity);
    }

    /** 实体循环开始：为本维度 tick 建立实体子任务屏障（仅 worker 线程调用）。 */
    public static void begin() {
        CURRENT.set(FineGrainScheduler.beginBarrier());
    }

    /**
     * 实体循环结束（finally 中调用）：分片模式先按片 Claim 提交（慢片掉拍延后），
     * 再等待已提交的子任务全部完成并执行延迟回调，清除上下文。
     */
    public static void end() {
        FineGrainScheduler.Barrier barrier = CURRENT.get();
        if (barrier == null) {
            return;
        }
        CURRENT.remove();
        Batch batch = BATCH.get();
        BATCH.remove();
        if (batch != null) {
            if (sharded) {
                ShardGate gate = GATES.computeIfAbsent(batch.dimension, key -> new ShardGate());
                List<Entity>[] shards = batch.allShards();
                for (int s = 0; s < ShardGate.SHARDS; s++) {
                    List<Entity> entities = shards[s];
                    if (entities == null || entities.isEmpty()) {
                        continue;
                    }
                    if (gate.tryAcquire(s)) {
                        barrier.submitShard(batch.server, gate, s,
                                () -> modzuozhi_tickShard(entities, batch.consumer));
                    } else {
                        MISSED_SHARDS.increment();
                        ModZuozhi.LOGGER.debug("[EntityTick] 实体分片 {} 掉拍(上 tick 未完成)，整体延后 1 tick", s);
                    }
                }
            } else {
                List<Entity> all = batch.merged();
                if (!all.isEmpty()) {
                    barrier.submitBatch(batch.server, all, batch.consumer);
                }
            }
        }
        barrier.await();
        barrier.drain();
    }

    /** 分片内逐实体 tick：单实体异常隔离（与批式路径语义一致），异常计入 FaultGuard。 */
    private static void modzuozhi_tickShard(List<Entity> entities, Consumer<Entity> consumer) {
        long t0 = System.nanoTime();
        for (Entity entity : entities) {
            try {
                if (!entity.isRemoved()) {
                    consumer.accept(entity);
                }
            } catch (Throwable t) {
                ModZuozhi.LOGGER.error("[EntityTick] 实体分片子任务异常", t);
                FaultGuard.onSubTaskFailure();
            }
        }
        // 观测型掉拍：本片负载没能在单 tick 预算内处理完（慢片），累计一次。
        if (System.nanoTime() - t0 > ShardGate.TICK_BUDGET_NS) {
            MISSED_SHARDS.increment();
        }
    }
}
