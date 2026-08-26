package dev.modzuozhi.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.modzuozhi.core.dimthread.DimThreadCore;
import dev.modzuozhi.core.dimthread.FineGrainScheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PortalProcessor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.portal.DimensionTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 通用传送门延后修复（覆盖原版下界门与模组传送门，如暮色森林）。
 * <p>
 * 根因：{@code Entity.handlePortal → PortalProcessor.getPortalDestination →
 * portal.getPortalDestination} 在<strong>维度 worker 线程</strong>（实体 tick 上下文）
 * 执行，会对<strong>目标维度</strong>区块做大量读写。目标区块尚未 FULL 加载时：
 * <ul>
 *   <li>下界门（{@link NetherPortalBlock} → {@code PortalForcer}）：getBlockState 等
 *       required=false 读不到真实地形 → createPortal 失败 → transition 为 null → 传送失效；</li>
 *   <li>模组门（如 {@code twilightforest.block.TFPortalBlock} → {@code TFTeleporter}）：
 *       直接 {@code ServerLevel.getChunk(x,z)}（required=true）→ worker 在同步路径
 *       {@code mainThreadProcessor.managedBlock} 上忙等目标区块生成，暮色区块生成慢，
 *       表现为卡约一分钟；且首次生成中数据不完整，传送门位置算错 → 被传到虚空。</li>
 * </ul>
 * <p>
 * 修复：在统一入口 {@code PortalProcessor.getPortalDestination} 上，若当前是维度 worker /
 * 细粒度子任务线程，且目标维度对应区块尚未 FULL 加载，则先投递 PORTAL ticket 触发目标
 * 维度异步生成，清传送冷却（否则 handlePortal 已设置的 300tick 冷却会阻断重试），并返回
 * null 把传送<strong>延后一 tick</strong>。玩家持续站在传送门中，portalTime 保持满值，
 * 下一 tick 自动重试——目标区块 FULL 后走原版逻辑：getChunk 命中完整缓存（不阻塞、位置
 * 正确），正常创建传送门并传送。全程不阻塞、不忙等、不刷日志，杜绝死锁与虚空。
 * <p>
 * 目标维度通过 portal 实例推导：下界门 ↔ 主世界/下界；暮色门 ↔ 主世界/暮色（用类名反射，
 * 避免编译期依赖模组类，未安装暮色时自然跳过）。
 */
@Mixin(PortalProcessor.class)
public abstract class PortalProcessorMixin {

    /** 暮色传送门类名（反射判断，避免编译期依赖模组类）。 */
    @Unique
    private static final String TF_PORTAL_CLASS = "twilightforest.block.TFPortalBlock";
    /** 暮色维度 key。 */
    @Unique
    private static final ResourceKey<Level> TF_DIMENSION =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("twilightforest", "twilight_forest"));

    @Shadow
    private Portal portal;

    @Shadow
    private int portalTime;

    @Shadow
    private BlockPos entryPosition;

    /**
     * 预加载目标维度区块：玩家站在传送门中、portalTime 累积期间（暮色默认 60tick）每 tick
     * 投递 PORTAL ticket，让目标区块提前异步生成。这样玩家 portalTime 满、真正触发
     * {@code getPortalDestination} 时目标区块大概率已 FULL → 直接传送，无需延后等待。
     * ticket 幂等（DistanceManager 按 type+pos+radius 合并），重复投递无害。
     */
    @Inject(method = "processPortalTeleportation", at = @At("HEAD"))
    private void modzuozhi_preloadPortalTarget(
            ServerLevel level, Entity entity, boolean canUse, CallbackInfoReturnable<Boolean> cir) {
        if (!DimThreadCore.owns(Thread.currentThread()) && !FineGrainScheduler.inSubTask()) {
            return;
        }
        ServerLevel target = modzuozhi_guessTargetLevel(level);
        if (target == null) {
            return;
        }
        double scale = DimensionType.getTeleportationScale(level.dimensionType(), target.dimensionType());
        BlockPos targetPos = target.getWorldBorder()
                .clampToBounds(entity.getX() * scale, entity.getY(), entity.getZ() * scale);
        ServerChunkCache cache = target.getChunkSource();
        int cx = targetPos.getX() >> 4;
        int cz = targetPos.getZ() >> 4;
        if (modzuozhi_needTargetChunks(cache, cx, cz)) {
            cache.addRegionTicket(TicketType.PORTAL, new ChunkPos(cx, cz), modzuozhi_portalRadius(), targetPos);
        }
    }

    /**
     * 包裹 {@code portal.getPortalDestination(...)}（实际执行 PortalForcer / TFTeleporter）。
     * <p>
     * 放行（目标区块已 FULL）后，暮色 TFTeleporter 在玩家路径（默认进度锁定
     * {@code ENFORCED_PROGRESSION_RULE=true}）会走 {@code moveToSafeCoords →
     * scanIntoSafeBiomes} 扫描 128 格，读取预加载范围<strong>之外</strong>的未生成区块 →
     * {@code getChunk(required=true)} 抛 {@code IllegalStateException: Chunk not there} →
     * 传播到玩家 tick 被 {@code LevelTickLoop.tickConnections} 捕获跳过 → 每 tick 循环
     * "放行→抛异常→跳过" → 玩家永久卡在传送门（生物不查进度走安全路径故能传，丢生物后
     * 目标区域已生成故玩家第二次成功）。
     * <p>
     * 这里捕获该异常：投递更大范围 PORTAL ticket 预加载 + 清冷却 + portalTime 防衰减 +
     * 返回 null 延后一 tick。下一 tick 重试时目标区域渐进生成，最终 TFTeleporter 找到安全
     * 位置完成传送。不掩盖其它真实异常。
     */
    @WrapOperation(method = "getPortalDestination",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/Portal;getPortalDestination(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/portal/DimensionTransition;"))
    private DimensionTransition modzuozhi_wrapPortalDestination(
            Portal portal, ServerLevel level, Entity entity, BlockPos pos, Operation<DimensionTransition> original) {
        if (!DimThreadCore.owns(Thread.currentThread()) && !FineGrainScheduler.inSubTask()) {
            return original.call(portal, level, entity, pos);
        }
        try {
            return original.call(portal, level, entity, pos);
        } catch (Throwable t) {
            if (!modzuozhi_isChunkNotReady(t)) {
                throw t; // 非区块未就绪异常：照常抛出，不掩盖真实 bug
            }
            // 目标区块未充分生成（暮色 TFTeleporter 玩家路径 scanIntafeBiomes 读远处区块）。
            // 不做大范围预加载（radius 6 会造成暮色 worker 疯狂生成 → 游戏冻结），
            // 由 ServerChunkCacheGetChunkFallbackMixin 对"被请求的单块"投 PORTAL ticket 渐进生成；
            // 这里仅清冷却 + portalTime 防衰减 + 延后一 tick，让下一 tick 重试时渐进推进。
            if (modzuozhi_logged.add("chunkMiss@" + entity.getId() + "@" + level.dimension().location())) {
                DimThreadCore.LOGGER.info("[PortalProcessor] 目标区块未充分生成(Chunk not there)，延后渐进生成: 原因={}", t.getMessage());
            }
            entity.setPortalCooldown(0);
            this.portalTime = 1_000_000_000;
            return null; // 延后，下一 tick 重试（单块渐进生成后最终成功）
        }
    }

    @Inject(method = "getPortalDestination", at = @At("HEAD"), cancellable = true)
    private void modzuozhi_deferPortalUntilTargetChunkLoaded(
            ServerLevel level, Entity entity, CallbackInfoReturnable<DimensionTransition> cir) {
        // 仅维度 worker / 细粒度子任务线程需要延后（主线程/串行可同步生成目标区块，保持原版语义）
        if (!DimThreadCore.owns(Thread.currentThread()) && !FineGrainScheduler.inSubTask()) {
            return;
        }
        ServerLevel target = modzuozhi_guessTargetLevel(level);
        if (target == null) {
            if (modzuozhi_logged.add("noTarget@" + level.dimension().location() + "@" + this.portal.getClass().getName())) {
                DimThreadCore.LOGGER.info("[PortalProcessor] 无法确定目标维度，不干预: portal={}, 当前维度={}",
                        this.portal.getClass().getName(), level.dimension().location());
            }
            return; // 无法确定目标维度（未知传送门），不干预
        }
        double scale = DimensionType.getTeleportationScale(level.dimensionType(), target.dimensionType());
        BlockPos targetPos = target.getWorldBorder()
                .clampToBounds(entity.getX() * scale, entity.getY(), entity.getZ() * scale);
        ServerChunkCache cache = target.getChunkSource();
        int cx = targetPos.getX() >> 4;
        int cz = targetPos.getZ() >> 4;
        // 目标区块（及 5x5 周围区域，暮色 TFTeleporter 会同步 getChunk 5x5）尚未 FULL 加载：
        // 投递 PORTAL ticket 触发异步生成，本 tick 延后传送
        if (modzuozhi_needTargetChunks(cache, cx, cz)) {
            cache.addRegionTicket(TicketType.PORTAL, new ChunkPos(cx, cz), modzuozhi_portalRadius(), targetPos);
            // 关键1：handlePortal 在 getPortalDestination 之前已调用 setPortalCooldown()（300tick 冷却）。
            // 若不清冷却，玩家持续站传送门时 setAsInsidePortal 走冷却分支、不再置
            // insidePortalThisTick=true → processPortalTeleportation 返回 false → getPortalDestination
            // 不再重试 → 只能等冷却结束、脱离再进传送门才成功。清冷却后下 tick 保留 portalProcess
            // 并重试 getPortalDestination，目标区块 FULL 后立即传送。
            entity.setPortalCooldown(0);
            // 关键2：延后期间 handlePortal 与 entityInside 的执行顺序导致 insidePortalThisTick
            // 隔 tick 才 true → processPortalTeleportation 每 2 tick 触发、每 2 tick decayTick -4
            // 而 portalTime++ 只 +1 → portalTime 净衰减 → 衰减到 < transitionTime(玩家 60) →
            // processPortalTeleportation false → hasExpired → portalProcess 被清除 → 重试彻底中断
            // （玩家第一次进传送门失效的真因；村民 transitionTime=0 恒 >=0 不衰减故能持续重试）。
            // 把 portalTime 拉到安全大值，确保延后期间永不衰减失效，区块 FULL 后必然传送成功。
            this.portalTime = 1_000_000_000;
            if (modzuozhi_logged.add("defer@" + entity.getId() + "@" + cx + "," + cz)) {
                DimThreadCore.LOGGER.info("[PortalProcessor] 传送门延后: 目标维度={}, 区块=({},{}), 等待目标区块FULL后自动重试",
                        target.dimension().location(), cx, cz);
            }
            cir.setReturnValue(null);
        } else if (modzuozhi_logged.add("ready@" + entity.getId() + "@" + cx + "," + cz)) {
            // 目标区块已 FULL：放行走原逻辑（诊断用，确认延后最终放行）
            DimThreadCore.LOGGER.info("[PortalProcessor] 目标区块已就绪放行: 目标维度={}, 区块=({},{}), 走原版传送逻辑",
                    target.dimension().location(), cx, cz);
        }
    }

    /** 目标区块 5x5（±2）范围内是否有未 FULL 加载的区块（暮色 TFTeleporter 需同步读 5x5）。 */
    @Unique
    private static boolean modzuozhi_needTargetChunks(ServerChunkCache cache, int cx, int cz) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (cache.getChunkNow(cx + dx, cz + dz) == null) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 已打印过的诊断日志（防止刷屏）。 */
    @Unique
    private static final java.util.Set<String> modzuozhi_logged = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 依据 portal 实例推导目标维度；未知传送门返回 null（不干预）。 */
    @Unique
    private ServerLevel modzuozhi_guessTargetLevel(ServerLevel level) {
        MinecraftServer server = level.getServer();
        ResourceKey<Level> dim = level.dimension();
        if (this.portal instanceof NetherPortalBlock) {
            return server.getLevel(dim == Level.NETHER ? Level.OVERWORLD : Level.NETHER);
        }
        if (this.portal.getClass().getName().equals(TF_PORTAL_CLASS)) {
            return server.getLevel(dim == TF_DIMENSION ? Level.OVERWORLD : TF_DIMENSION);
        }
        return null;
    }

    /**
     * 预加载半径：暮色传送门较大（TFTeleporter 玩家路径 scanIntoSafeBiomes 会扫 128 格，
     * 覆盖半径约 6 区块），下界门只需中心区域（半径 2）。
     */
    @Unique
    private int modzuozhi_portalRadius() {
        return this.portal.getClass().getName().equals(TF_PORTAL_CLASS) ? 6 : 2;
    }

    /** 是否为"区块未就绪"（Chunk not there when requested）瞬时异常（遍历 cause 链）。 */
    @Unique
    private static boolean modzuozhi_isChunkNotReady(Throwable t) {
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
}
