package dev.modzuozhi.core.dimthread;

import com.mojang.logging.LogUtils;
import dev.modzuozhi.ModZuozhi;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Unit;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 阶段 A（步骤 0）：自适应区块预取。
 * <p>
 * 原理：在玩家 tick（主线程 {@code playerList.tick()} → {@code ServerPlayer.doTick()}）时，
 * 根据玩家移动方向，对<b>前方</b>尚未加载的区块投递一个<b>临时的 FULL 级 ticket</b>，让原版
 * 区块生成管线提前把该区块生成好；玩家到达时直接命中已生成缓存，无需现场等待。
 * <p>
 * 安全 / 无泄漏：
 * <ul>
 *   <li>使用<b>自定义 {@link TicketType}</b>（{@code timeout}=10s），到期由
 *       {@code DistanceManager.purgeStaleTickets()} 自动回收，无需手动 remove，不存在 ticket 泄漏；</li>
 *   <li>只在玩家<b>实际移动</b>时、且方向可确定时才预取，静止/回退不产生任何 ticket；</li>
 *   <li>每玩家每 {@link #PREFETCH_INTERVAL_TICKS} 才重算一次，避免逐 tick 开销；</li>
 *   <li>已 FULL 的区块直接跳过（{@code hasChunk}），不重复下 ticket；</li>
 *   <li>全流程 try/catch，预取任何异常都不影响玩家 tick（纯增益，可安全回退）。</li>
 * </ul>
 * <p>
 * 不碰生成管线、不碰地形生成 mod：这里只负责"提前下票触发原版生成"，生成逻辑完全交给原版。
 */
public final class ChunkPrefetcher {

    /** 预取总开关（与 {@link ModZuozhi#isEnabled()} 叠加，一键回退）。 */
    public static volatile boolean ENABLED = true;

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 预取 ticket 存活时长（tick），到期自动回收。10s 覆盖通常的前视时间窗。 */
    private static final int PREFETCH_TIMEOUT_TICKS = 200;

    /** 每次重算至少要移动这么多格（平方距离）才触发，过滤原地抖动。 */
    private static final double MIN_MOVE_SQ = 1.0;

    /** 预取重算间隔（tick），降低每个玩家的逐 tick 开销。 */
    private static final int PREFETCH_INTERVAL_TICKS = 5;

    /** 预取深度：在玩家视距之外，再向前预取的区块数（收敛为 4~6 格，避免过度预取浪费算力）。 */
    private static final int PREFETCH_DEPTH = 6;

    /** 自定义预取 ticket：FULL 级别 + 有限存活时间。 */
    private static final TicketType<Unit> PREFETCH_TICKET =
            TicketType.create("dimensionalripper_prefetch", (a, b) -> 0, PREFETCH_TIMEOUT_TICKS);

    /** 每玩家轻量状态（上次位置 / 上次所在 chunk / 上次重算游戏刻）。 */
    private static final ConcurrentHashMap<UUID, State> STATES = new ConcurrentHashMap<>();

    /** 状态表容量兜底，超出即整体清空（丢失仅损失一次方向估计，无害）。 */
    private static final int STATE_CAPACITY = 512;

    private ChunkPrefetcher() {
    }

    /** 每次玩家 tick 调用一次（主线程）。 */
    public static void onPlayerTick(ServerPlayer player) {
        if (!ModZuozhi.isEnabled() || !ENABLED) {
            return;
        }
        try {
            tick(player);
        } catch (Throwable t) {
            // 预取是纯增益：任何异常都不应影响玩家 tick 或服务器稳定性
        }
    }

    private static void tick(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        ServerChunkCache source = level.getChunkSource();

        State state = STATES.computeIfAbsent(player.getUUID(), u -> new State());
        if (STATES.size() > STATE_CAPACITY) {
            STATES.clear();
        }

        // 首次记录位置快照，不产生方向估计
        if (!state.initialized) {
            state.init(player, level.getGameTime());
            return;
        }

        long gameTime = level.getGameTime();
        if (gameTime - state.lastGameTime < PREFETCH_INTERVAL_TICKS) {
            return;
        }
        state.lastGameTime = gameTime;

        double x = player.getX();
        double z = player.getZ();
        double dx = x - state.lastX;
        double dz = z - state.lastZ;
        state.lastX = x;
        state.lastZ = z;

        if (dx * dx + dz * dz < MIN_MOVE_SQ) {
            return; // 原地不动，不预取
        }

        ChunkPos chunk = player.chunkPosition();
        int dirX = Integer.compare(chunk.x, state.lastChunkX);
        int dirZ = Integer.compare(chunk.z, state.lastChunkZ);
        state.lastChunkX = chunk.x;
        state.lastChunkZ = chunk.z;

        if (dirX == 0 && dirZ == 0) {
            return; // 尚未跨 chunk，方向不可定
        }

        // 原版把区块生成到 FULL（完整）的半径由“视距 viewDistance”决定，不是模拟距离 simDistance。
        // 因此只对【视距外 1~PREFETCH_DEPTH 格】的固定窗口投递临时 ticket：视距内的区块已被玩家
        // 自身 ticket 加载到 FULL（hasChunk 恒真、下不出去票），窗口内已 FULL 的跳过即可。
        // 固定窗口（不再向前扫描）是为了把预取前沿钉死在 view+PREFETCH_DEPTH，避免生成速度过快时
        // 前沿被自己持续向前推进到十几二十格外，导致无谓的算力浪费。
        int viewDist = level.getServer().getPlayerList().getViewDistance();
        int simDist = level.getServer().getPlayerList().getSimulationDistance();
        int from = viewDist + 1;
        int to = viewDist + PREFETCH_DEPTH;
        int prefetched = 0;
        for (int k = from; k <= to; k++) {
            if (prefetch(source, chunk.x + dirX * k, chunk.z + dirZ * k)) {
                prefetched++;
            }
        }
        if (state.firstLog || gameTime - state.lastLogGameTime >= 20) {
            state.firstLog = false;
            state.lastLogGameTime = gameTime;
            LOGGER.info("[ChunkPrefetcher] 玩家 {} 方向 ({},{}), view={}, sim={}, 预取区间 [{}~{}], 投递 {} 个ticket",
                    player.getGameProfile().getName(), dirX, dirZ, viewDist, simDist, from, to, prefetched);
        }
    }

    private static boolean prefetch(ServerChunkCache source, int chunkX, int chunkZ) {
        if (source.hasChunk(chunkX, chunkZ)) {
            return false; // 已 FULL 加载，无需预取
        }
        // radius=0 → FULL 级别 ticket；到期自动回收，不阻塞、不泄漏
        source.addRegionTicket(PREFETCH_TICKET, new ChunkPos(chunkX, chunkZ), 0, Unit.INSTANCE);
        return true;
    }

    private static final class State {
        boolean initialized;
        double lastX;
        double lastZ;
        int lastChunkX;
        int lastChunkZ;
        long lastGameTime;
        long lastLogGameTime;
        boolean firstLog;

        void init(ServerPlayer player, long gameTime) {
            this.initialized = true;
            this.lastX = player.getX();
            this.lastZ = player.getZ();
            ChunkPos c = player.chunkPosition();
            this.lastChunkX = c.x;
            this.lastChunkZ = c.z;
            this.lastGameTime = gameTime;
            this.firstLog = true;
        }
    }
}