package dev.modzuozhi.core.filter;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实体 tick 并行的白名单过滤器（借鉴 MCMT 思路，写我们自己的实现）。
 * <p>
 * 实体 tick 远比方块实体复杂：实体互相交互、移动会更新 {@code EntitySectionStorage}、
 * 可能繁殖/转换产生新实体。因此第4步采用<b>保守白名单</b>——先以最复杂的村民（Brain AI）
 * 验证通过，再扩展为所有原版生物。
 * <p>
 * 判定规则（按优先级）：
 * <ol>
 *     <li>命中黑名单 {@link #BLACKLIST} → 串行（已知有问题的类型，可运行时扩展排除）；</li>
 *     <li>命中白名单 {@link #WHITELIST} → 并行（显式放行，运行时可用 {@link #add} 增补）；</li>
 *     <li>默认：{@code LivingEntity} 且非玩家且<b>原版生物</b> → 并行；其余（modded 生物、
 *         掉落物、经验球、箭、TNT、盔甲架等非生物实体）→ 串行。</li>
 * </ol>
 * modded 生物默认串行是借鉴 Async 的思路（其用 {@code @AsyncCompatible} 注解显式放行）：
 * 跨模组生物行为不可控，保守串行以降低并行下的兼容风险；确需并行时可用 {@link #add} 显式放行。
 * 玩家始终由 {@link PlayerList} 单独处理，不参与本并行路径。
 */
public final class EntityTickFilter {
    /** 显式放行名单：命中即并行（村民已验证，作为兜底显式保留）。 */
    private static final Set<String> WHITELIST = ConcurrentHashMap.newKeySet();

    /** 显式排除名单：命中即强制串行（覆盖默认规则，可用于运行时隔离问题生物）。 */
    private static final Set<String> BLACKLIST = ConcurrentHashMap.newKeySet();

    /**
     * 并行判定缓存：{@code EntityType → 是否并行}。
     * <p>
     * 避免每个实体每 tick 都 {@code EntityType.getKey(...).toString()} 构建字符串
     * （高实体量下是显著开销）。实体类型数量有限，首次遇到时计算一次并缓存；
     * 黑/白名单运行时变化时 {@link #PARALLEL_CACHE} 清空。
     */
    private static final ConcurrentHashMap<EntityType<?>, Boolean> PARALLEL_CACHE = new ConcurrentHashMap<>();

    static {
        // 第4步首期验证通过的最难实体（Brain AI），显式保留在放行名单。
        WHITELIST.add("minecraft:villager");

        // 第5步（6b/6c）：爆炸/破坏方块类实体<b>强制串行</b>。
        // 它们在 tick 中会调用 {@code Level.setBlock} 破坏方块（苦力怕自爆、凋灵、末影龙、
        // 末影水晶），这会写 LevelChunk 的 PalettedContainer（非线程安全）。若在并行子任务线程
        // 执行，会与主线程的玩家操作（放置方块）<b>并发写同一区块</b>，导致数据竞争：
        // 放置的方块立即消失、被破坏的方块在存档中"复活"（重进后 TNT 再次出现并爆炸）。
        // 因此这些实体必须走原版串行路径，与玩家操作在主线程时序上互斥。
        BLACKLIST.add("minecraft:creeper");
        BLACKLIST.add("minecraft:end_crystal");
        BLACKLIST.add("minecraft:ender_dragon");
        BLACKLIST.add("minecraft:wither");
        BLACKLIST.add("minecraft:tnt_minecart");
    }

    private EntityTickFilter() {
    }

    /** 判断实体是否可并行 tick。 */
    public static boolean isParallel(Entity entity) {
        // 快路径：非生物或玩家一定不并行（黑名单中的末影水晶/TNT矿车非生物，白名单只有村民）。
        if (!(entity instanceof LivingEntity) || entity instanceof Player) {
            return false;
        }
        EntityType<?> type = entity.getType();
        Boolean cached = PARALLEL_CACHE.get(type);
        if (cached != null) {
            return cached;
        }
        boolean result = computeParallel(type);
        PARALLEL_CACHE.put(type, result);
        return result;
    }

    /** 首次遇到某实体类型时计算一次判定（含黑/白名单与命名空间判断）。 */
    private static boolean computeParallel(EntityType<?> type) {
        String key = EntityType.getKey(type).toString();
        if (BLACKLIST.contains(key)) {
            return false;
        }
        if (WHITELIST.contains(key)) {
            return true;
        }
        // 默认：原版（minecraft 命名空间）生物并行；modded 生物串行（借鉴 Async 思路）。
        return EntityType.getKey(type).getNamespace().equals("minecraft");
    }

    /** 显式放行某实体类型（运行时扩展）。 */
    public static void add(String entityType) {
        WHITELIST.add(entityType);
        BLACKLIST.remove(entityType);
        PARALLEL_CACHE.clear();
    }

    /** 显式排除某实体类型（运行时隔离问题生物）。 */
    public static void exclude(String entityType) {
        BLACKLIST.add(entityType);
        WHITELIST.remove(entityType);
        PARALLEL_CACHE.clear();
    }

    public static boolean remove(String entityType) {
        PARALLEL_CACHE.clear();
        return WHITELIST.remove(entityType);
    }

    public static boolean contains(String entityType) {
        return WHITELIST.contains(entityType);
    }

    public static boolean isExcluded(String entityType) {
        return BLACKLIST.contains(entityType);
    }
}
