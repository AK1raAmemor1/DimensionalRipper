package dev.modzuozhi.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.modzuozhi.ModZuozhi;
import dev.modzuozhi.core.dimthread.ChunkLock;
import dev.modzuozhi.core.dimthread.DimThreadCore;
import dev.modzuozhi.core.dimthread.EntityTickParallel;
import dev.modzuozhi.core.dimthread.FaultGuard;
import dev.modzuozhi.core.dimthread.FineGrainScheduler;
import dev.modzuozhi.core.dimthread.IChunkEnvTick;
import dev.modzuozhi.core.dimthread.IMutableMainThread;
import dev.modzuozhi.core.dimthread.ShardGate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * dimthreads 式 {@code ServerChunkCache} 线程伪装。
 * <p>
 * 在 {@code swapThreadsAndRun} 期间，本维度的 {@code mainThread} 已被临时设为当前 worker，
 * 因此 worker 上 {@code Thread.currentThread() == mainThread} 自然成立，{@code getChunk}
 * 走同步路径（{@code managedBlock} 驱动本维度 chunkMap），不会投递回服务器主线程并
 * {@code join} 死锁。
 * <p>
 * 当某个维度 worker 需要访问<strong>其它维度</strong>的区块（如下界门查找目标传送门）时，
 * 当前线程（A worker）!= 目标维度 mainThread（B worker），原版会走
 * {@code getChunkFutureMainThread + managedBlock + join} 慢路径。这里把
 * {@code Thread.currentThread()} 伪装成 {@code this.mainThread}，使 A 直接同步读取 B 维度
 * 已加载区块，避免跨维度 supplyAsync/join 死锁。同时实现 {@link IMutableMainThread}，
 * 供 {@code swapThreadsAndRun} 临时迁移主线程引用。
 */
@Mixin(value = ServerChunkCache.class, priority = 1001)
public abstract class ServerChunkCacheMixin implements IMutableMainThread {
    @Shadow
    public Thread mainThread;
    @Shadow
    @Final
    public ServerLevel level;

    @Override
    @Unique
    public Thread dimThreads$getMainThread() {
        return this.mainThread;
    }

    @Override
    @Unique
    public void dimThreads$setMainThread(Thread thread) {
        this.mainThread = thread;
    }

    @WrapOperation(method = "getChunk",
            at = @At(value = "INVOKE", target = "Ljava/lang/Thread;currentThread()Ljava/lang/Thread;"))
    public Thread modzuozhi_currentThread(Operation<Thread> original) {
        return modzuozhi_threadDisguise(original);
    }

    /**
     * {@code getChunkNow} 同样有 {@code Thread.currentThread() != mainThread → return null} 的线程守卫。
     * 碰撞检测（{@code BlockCollisions}）通过 {@code Level.getChunkForCollisions} 走 {@code getChunkNow}，
     * 子任务线程若被守卫拦截返回 null，实体的方块碰撞被整体跳过 → 穿墙、遁地、卡进方块窒息。
     * 这里与 {@code getChunk} 一样伪装当前线程，使子任务线程能读取到已加载区块的完整碰撞形状。
     */
    @WrapOperation(method = "getChunkNow",
            at = @At(value = "INVOKE", target = "Ljava/lang/Thread;currentThread()Ljava/lang/Thread;"))
    public Thread modzuozhi_currentThreadNow(Operation<Thread> original) {
        return modzuozhi_threadDisguise(original);
    }

    /**
     * 维度 worker / 细粒度子任务线程伪装成该 cache 的主线程，走同步路径：
     * <ul>
     *     <li>{@code getChunk}：避免 supplyAsync/join 死锁；</li>
     *     <li>{@code getChunkNow}：避免碰撞检测被线程守卫拦截返回 null（穿墙/遁地根因）。</li>
     * </ul>
     */
    @Unique
    private Thread modzuozhi_threadDisguise(Operation<Thread> original) {
        Thread thread = original.call();
        if (DimThreadCore.MANAGER.isActive(this.level.getServer())
                && (DimThreadCore.owns(thread) || FineGrainScheduler.inSubTask())) {
            return this.mainThread;
        }
        return thread;
    }

    // ===== 并行子任务：绕过撕裂的共享 lastChunk 缓存 =====
    //
    // 根因：细粒度并行时，多个子任务线程 + 维度 worker 会<strong>并发</strong>读写
    // ServerChunkCache 的 4 槽 lastChunkPos/lastChunkStatus/lastChunk 缓存（普通数组，非线程安全）。
    // storeInCache 的移位写入与 getChunk 的读取交错时会产生"撕裂读"：getChunk(x,z) 命中了
    // pos/status 槽但拿到的是<strong>另一个坐标</strong>的区块对象。碰撞检测（BlockCollisions →
    // ChunkSource.getBlockState）拿着错块去读方块状态，会把脚下的实心地面读成空气 → 村民穿地/遁地/
    // 卡进方块窒息。诊断（getChunk(FULL,false) 返回"wrong 错块"计数持续增长）已证实。
    //
    // 修复：子任务线程上完全绕过共享缓存——getChunk / getChunkNow 直接按坐标从 ChunkHolder
    // （visibleChunkMap 是 volatile 快照式引用，get 线程安全）读正确区块；storeInCache 对子任务
    // 线程为 no-op，避免污染共享缓存。主线程 / 维度 worker / 串行场景行为不变（零开销）。

    /**
     * 子任务线程的 {@code getChunk}：绕过 lastChunk 缓存，直接读正确坐标的 FULL 区块。
     * <p>
     * <b>第10步（修复撕裂回退 + 修复死锁）</b>：
     * <ul>
     *   <li>FULL 命中：走 {@link #modzuozhi_subtaskCacheLookup}，绝不用共享 4 槽 lastChunk 缓存
     *       （多线程并发写会撕裂，拿到其它坐标区块 → 碰撞把地面读成空气 → 村民遁地）。</li>
     *   <li>FULL 未命中（区块尚未 FULL，多为加载/生成中）：<b>非阻塞</b>占位返回，<b>绝不能等待</b>——
     *       区块生成管线由维度 worker 的 {@code chunkSource.tick} / 服务器主线程驱动，而这两者此刻
     *       分别卡在「实体屏障 await」和「维度 awaitCompletion」上；等待生成 future 会形成
     *       循环等待 → 整服死锁（日志：[FaultGuard] 维度 tick 超时 >30000ms，玩家侧表现为游戏冻结）。
     *       返回占位块后，区块生成完的下一 tick 命中缓存、碰撞恢复正常，短暂下坠自愈，绝不冻结。</li>
     * </ul>
     */
    @WrapMethod(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;")
    private ChunkAccess modzuozhi_subtaskSafeGetChunk(
            int x, int z, ChunkStatus status, boolean required, Operation<ChunkAccess> original) {
        if (!FineGrainScheduler.inSubTask()) {
            return original.call(x, z, status, required);
        }
        if (status != ChunkStatus.FULL) {
            return original.call(x, z, status, required);
        }
        LevelChunk full = modzuozhi_subtaskCacheLookup(x, z);
        if (full != null) {
            return full;
        }
        // 未 FULL：统一返回实心占位（石头），不再区分 required。
        // 根因：并行子任务线程对未生成区块的碰撞检测（required=false → 原返回 null → 碰撞整体
        // 跳过）与寻路/方块读取（required=true → 原返回空气占位）都把未生成区块当作"空气/无地面"，
        // 村民会持续下坠穿入未生成区块 → 加载高峰期遁地（已实测：fine off 串行时 getChunk 会
        // join 同步生成拿真实方块故不遁地，仅并行路径有此问题）。
        // 实心占位让未生成区块表现为"实心墙"：村民被挡在生成边界，区块 FULL 后恢复正常。
        // 仍保持非阻塞（不等待生成 future，避免维度 worker 被 barrier 卡住的循环等待死锁）。
        return modzuozhi_solidChunk(x, z);
    }

    /** 构造一个所有方块读作石头的实心占位区块（非阻塞、线程安全，避免生成等待死锁）。 */
    @Unique
    private LevelChunk modzuozhi_solidChunk(int x, int z) {
        Holder<Biome> plains = this.level.registryAccess()
                .registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS);
        return new ModZuozhiSolidChunk(this.level, new ChunkPos(x, z), plains);
    }

    /**
     * 未 FULL 区块的实心占位：重写 {@code getBlockState} 恒返回石头。
     * 并行子任务线程把未生成区块表现为实心墙，防止实体下坠穿入（遁地）。
     */
    @Unique
    private static final class ModZuozhiSolidChunk extends EmptyLevelChunk {
        private ModZuozhiSolidChunk(ServerLevel level, ChunkPos pos, Holder<Biome> biome) {
            super(level, pos, biome);
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return Blocks.STONE.defaultBlockState();
        }
    }

    /**
     * 子任务线程的 {@code getChunkNow}：同样绕过 lastChunk 缓存（fallback 的
     * {@code modzuozhi_tryGetChunkNow} 也依赖它），避免撕裂读返回错块。
     */
    @WrapMethod(method = "getChunkNow(II)Lnet/minecraft/world/level/chunk/LevelChunk;")
    private LevelChunk modzuozhi_subtaskSafeGetChunkNow(int x, int z, Operation<LevelChunk> original) {
        if (!FineGrainScheduler.inSubTask()) {
            return original.call(x, z);
        }
        return modzuozhi_subtaskCacheLookup(x, z);
    }

    /**
     * 子任务线程的 {@code storeInCache}：no-op，避免污染（撕裂）主线程/worker 共享的 lastChunk 缓存。
     */
    @WrapMethod(method = "storeInCache(JLnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/chunk/status/ChunkStatus;)V")
    private void modzuozhi_subtaskSafeStoreInCache(
            long pos, ChunkAccess chunk, ChunkStatus status, Operation<Void> original) {
        if (!FineGrainScheduler.inSubTask()) {
            original.call(pos, chunk, status);
        }
        // 子任务线程：不写共享缓存
    }

    // ===== 第5步（6c）：子任务线程专用并发区块缓存 =====
    //
    // 目标：减少子任务线程高频 getChunkNow/getChunk(FULL)（碰撞检测、AI 传感器）的
    // ChunkHolder future 解析开销与共享缓存撕裂风险。设计要点：
    //   1. 只缓存 FULL 状态的 LevelChunk（碰撞/AI 只读完整区块）；
    //   2. 用 ConcurrentHashMap（线程安全）按 chunk pos 缓存，天然与维度隔离（per-cache 字段）；
    //   3. 命中前先校验 ChunkHolder 仍可见（区块未卸载），杜绝返回已卸载/失效区块；
    //   4. 位置校验（x/z 匹配）双保险，防止任何错块路径（吸取 lastChunk 撕裂教训）；
    //   5. 超过阈值时清空，避免长期运行内存膨胀（区块生命周期短，全清可接受）。

    /** 子任务线程专用 FULL 区块缓存：chunkPos(long) → LevelChunk。 */
    @Unique
    private final ConcurrentHashMap<Long, LevelChunk> modzuozhi_subtaskChunkCache = new ConcurrentHashMap<>();

    /** 缓存条目上限，超过即整表清空。 */
    @Unique
    private static final int MODZUOZHI_CACHE_MAX = 8192;

    /**
     * 子任务线程读取 FULL 区块：优先命中缓存，未命中则从 ChunkHolder 读取并回填缓存。
     * <p>
     * <b>正确性关键</b>：本方法不仅被「只读」路径（碰撞检测、AI 传感器）调用，也会被
     * <b>写入</b>路径（爆炸破坏方块 → {@code Level.setBlock → getChunkAt → getChunk(FULL)}）
     * 调用。因此返回的 {@code LevelChunk} <b>必须是 ChunkHolder 当前活动对象</b>，否则
     * {@code setBlockState} 会写到已过期（重载前）的对象上，导致方块状态丢失/复活（例如
     * 爆炸破坏的 TNT 在存档中复活并再次爆炸）。
     * <p>
     * 校验策略：命中缓存后，用 {@code holder.getFullChunkFuture().getNow(...)} 解析当前活动
     * 对象，与缓存做<b>对象身份（==）</b>比较——一致才返回缓存（对象未变，安全）；
     * 不一致说明区块已重载，返回并回填新对象。completed future 的 {@code getNow} 开销可忽略，
     * 但彻底杜绝"写到过期对象"这一致数据损坏路径。
     */
    @Unique
    private LevelChunk modzuozhi_subtaskCacheLookup(int x, int z) {
        long key = ChunkPos.asLong(x, z);
        ChunkHolder holder = ((ServerChunkCacheAccessor) (Object) this)
                .modzuozhi_getVisibleChunkIfPresent(key);
        if (holder == null) {
            // 区块不在可见列表：不缓存（避免缓存已卸载区块），直接返回 null
            return null;
        }
        // 解析当前活动 FULL 区块（completed future，开销极低）
        LevelChunk full = holder.getFullChunkFuture().getNow(ChunkHolder.UNLOADED_LEVEL_CHUNK).orElse(null);
        if (full == null) {
            return null;
        }
        // 身份校验：缓存对象与当前活动对象一致才可复用（防止写已过期对象）
        LevelChunk cached = this.modzuozhi_subtaskChunkCache.get(key);
        if (cached != full) {
            this.modzuozhi_subtaskChunkCache.put(key, full);
            if (this.modzuozhi_subtaskChunkCache.size() > MODZUOZHI_CACHE_MAX) {
                this.modzuozhi_subtaskChunkCache.clear();
            }
        }
        return full;
    }

    // ===== 第3步：区块环境 tick（随机 tick / 流体）跨 chunk 并行 =====

    /** 并行开启且处于维度 worker 线程时才收集待并行的区块。 */
    @Unique
    private boolean modzuozhi_chunkParallel;
    /** 收集的待并行区块：(ServerLevel, LevelChunk, randomTickSpeed)。 */
    @Unique
    private final List<Object[]> modzuozhi_pendingChunks = new ArrayList<>();
    /** 本次 chunk tick 的子任务屏障。 */
    @Unique
    private FineGrainScheduler.Barrier modzuozhi_chunkBarrier;
    /** 区块环境分片门（跨 tick 存续，慢片掉拍时本 tick 该区块延后）。 */
    @Unique
    private ShardGate modzuozhi_chunkGate;

    @Unique
    private boolean modzuozhi_chunkShouldParallel() {
        if (!FineGrainScheduler.isEnabled()) {
            return false;
        }
        MinecraftServer server = this.level.getServer();
        return server != null
                && DimThreadCore.MANAGER.isActive(server)
                && DimThreadCore.owns(Thread.currentThread());
    }

    @Inject(method = "tickChunks", at = @At("HEAD"))
    private void modzuozhi_chunkBegin(CallbackInfo ci) {
        this.modzuozhi_chunkParallel = modzuozhi_chunkShouldParallel();
        this.modzuozhi_pendingChunks.clear();
    }

    /**
     * 拦截 {@code ServerChunkCache.tickChunks} 里对每个区块的 {@code level.tickChunk(...)}：
     * 并行开启时先收集到 {@link #modzuozhi_pendingChunks}，由 {@link #modzuozhi_chunkFlush}
     * 在广播前统一提交并行执行；关闭时直接串行执行，行为与原版完全一致。
     */
    @Redirect(method = "tickChunks",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;tickChunk(Lnet/minecraft/world/level/chunk/LevelChunk;I)V"))
    private void modzuozhi_chunkTick(ServerLevel level, LevelChunk chunk, int randomTickSpeed) {
        if (this.modzuozhi_chunkParallel) {
            this.modzuozhi_pendingChunks.add(new Object[]{level, chunk, randomTickSpeed});
        } else {
            level.tickChunk(chunk, randomTickSpeed);
        }
    }

    /**
     * 在 {@code tickChunks} 的 {@code list.forEach(... broadcastChanges ...)} 之前收口：
     * 把收集的区块并行执行随机 tick / 流体，并 {@code await} 全部完成，确保方块状态变化
     * 在广播前已应用。
     */
    @WrapOperation(method = "tickChunks",
            at = @At(value = "INVOKE",
                    target = "Ljava/util/List;forEach(Ljava/util/function/Consumer;)V"))
    private void modzuozhi_chunkFlush(List<?> list, Consumer<Object> consumer, Operation<Void> original) {
        this.modzuozhi_flushChunkTicks();
        original.call(list, consumer);
    }

    @Unique
    private void modzuozhi_flushChunkTicks() {
        if (!this.modzuozhi_chunkParallel || this.modzuozhi_pendingChunks.isEmpty()) {
            this.modzuozhi_chunkParallel = false;
            return;
        }
        MinecraftServer server = this.level.getServer();
        this.modzuozhi_chunkBarrier = FineGrainScheduler.beginBarrier();
        this.modzuozhi_chunkGate = ShardGate.chunkGate(this.level.dimension());
        boolean sharded = EntityTickParallel.isSharded();
        if (sharded) {
            // 分片模式：先按区块坐标稳定散列聚合收集，收口时每片只 tryAcquire 一次。
            // 逐区块 tryAcquire 会让同片第 2 个起的区块全部被同拍竞争误判为掉拍（永不随机 tick）。
            List<Object[]>[] shards = new List[ShardGate.SHARDS];
            for (Object[] entry : this.modzuozhi_pendingChunks) {
                LevelChunk chunk = (LevelChunk) entry[1];
                ChunkPos cpos = chunk.getPos();
                int s = ShardGate.shardOf(ChunkPos.asLong(cpos.x, cpos.z));
                List<Object[]> list = shards[s];
                if (list == null) {
                    list = new ArrayList<>();
                    shards[s] = list;
                }
                list.add(entry);
            }
            for (int s = 0; s < ShardGate.SHARDS; s++) {
                List<Object[]> list = shards[s];
                if (list == null || list.isEmpty()) {
                    continue;
                }
                if (this.modzuozhi_chunkGate.tryAcquire(s)) {
                    this.modzuozhi_chunkBarrier.submitShard(server, this.modzuozhi_chunkGate, s,
                            () -> modzuozhi_tickChunkShard(list));
                } else {
                    ShardGate.bumpChunkMissed();
                    ModZuozhi.LOGGER.debug("[ChunkEnv] 分片 {} 掉拍(上 tick 未完成)，整片延后 1 tick", s);
                }
            }
        } else {
            for (Object[] entry : this.modzuozhi_pendingChunks) {
                ServerLevel level = (ServerLevel) entry[0];
                LevelChunk chunk = (LevelChunk) entry[1];
                int randomTickSpeed = (Integer) entry[2];
                Runnable task = () -> {
                    // 线程本地随机源，避免并行子任务跨线程访问共享 Level.random
                    ((IChunkEnvTick) level).modzuozhi_tickChunk(chunk, randomTickSpeed, RandomSource.create());
                };
                this.modzuozhi_chunkBarrier.submit(server, task, chunk.getPos().getWorldPosition());
            }
        }
        this.modzuozhi_pendingChunks.clear();
        this.modzuozhi_chunkBarrier.await();
        this.modzuozhi_chunkBarrier.drain();
        this.modzuozhi_chunkBarrier = null;
        this.modzuozhi_chunkGate = null;
        this.modzuozhi_chunkParallel = false;
    }

    /**
     * 分片内逐个区块做环境 tick：保持原逐区块提交的区块锁语义（防止与 TE / 实体阶段
     * 并发访问同一区块），每个区块独立线程本地随机源，单区块异常隔离。
     */
    @Unique
    private void modzuozhi_tickChunkShard(List<Object[]> list) {
        long t0 = System.nanoTime();
        for (Object[] entry : list) {
            ServerLevel level = (ServerLevel) entry[0];
            LevelChunk chunk = (LevelChunk) entry[1];
            int randomTickSpeed = (Integer) entry[2];
            try (AutoCloseable lock = ChunkLock.lock(chunk.getPos().getWorldPosition())) {
                ((IChunkEnvTick) level).modzuozhi_tickChunk(chunk, randomTickSpeed, RandomSource.create());
            } catch (Throwable t) {
                ModZuozhi.LOGGER.error("[ChunkEnv] 分片子任务异常", t);
                FaultGuard.onSubTaskFailure();
            }
        }
        // 观测型掉拍：本片负载没能在单 tick 预算内处理完（慢片），累计一次。
        if (System.nanoTime() - t0 > ShardGate.TICK_BUDGET_NS) {
            ShardGate.bumpChunkMissed();
        }
    }
}
