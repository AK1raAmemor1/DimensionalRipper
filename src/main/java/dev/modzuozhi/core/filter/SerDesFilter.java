package dev.modzuozhi.core.filter;

import net.minecraft.world.level.block.entity.TickingBlockEntity;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认 SerDes 过滤器。
 * <p>
 * 基于方块实体注册名（{@code TickingBlockEntity.getType()}，形如 {@code minecraft:furnace}）判断：
 * <ul>
 *     <li>黑名单（强制串行）：已知线程不安全、会与主线程交互的方块实体
 *         （如移动的活塞/活塞头，涉及跨区块移除与实体交互）。</li>
 *     <li>modded 方块实体（非 {@code minecraft:} 前缀）默认串行，保守兜底；
 *         可通过 {@link #setLockModded(boolean)} 关闭该保守行为，或通过
 *         {@link #whitelistModded(String)} 将确认线程安全的 modded TE 单独加入白名单。</li>
 *     <li>其余 vanilla 方块实体默认可并行。</li>
 * </ul>
 */
public final class SerDesFilter implements ISerDesFilter {
    /** 全局单例（{@link #isParallel} 为实例方法）。 */
    public static final SerDesFilter INSTANCE = new SerDesFilter();

    /** 强制串行的方块实体注册名（黑名单）。 */
    private static final Set<String> BLACKLIST = Set.of(
            "minecraft:piston_head",
            "minecraft:moving_piston",
            // 会跨 5×5 范围访问其他容器/方块并触发跨区块交互，worker 线程并行时
            // 目标区块可能被并发修改（如 InvWrapper.getInv() 返回 null），强制串行
            "minecraft:hopper",
            "minecraft:dropper",
            "minecraft:dispenser",
            "minecraft:chiseled_bookshelf",
            "minecraft:crafter",
            // serverTick 会访问 Level 级共享随机源（LegacyRandomSource），多个蜂巢分布在不同
            // 区块被并行 tick 时会跨线程访问同一随机源，触发 ThreadingDetector 异常，强制串行
            "minecraft:beehive",
            "minecraft:bee_nest"
    );

    /** 是否对非 vanilla 的 modded 方块实体强制串行（保守兜底，默认开启）。 */
    private static volatile boolean lockModded = true;

    /** 确认线程安全、可绕开 lockModded 强制串行的 modded TE 注册名白名单（运行时通过命令维护）。 */
    private static final Set<String> MODDED_WHITELIST = ConcurrentHashMap.newKeySet();

    /** 将某个 modded TE 注册名加入白名单，使其进入并行分片管线（本模组不保证其线程安全，加入前需自行确认）。 */
    public static void whitelistModded(String type) {
        MODDED_WHITELIST.add(type);
    }

    /** 移除白名单项（恢复默认的 modded 强制串行）；返回是否真的存在并移除了该项。 */
    public static boolean unwhitelistModded(String type) {
        return MODDED_WHITELIST.remove(type);
    }

    /** 当前白名单内容（诊断展示用）。 */
    public static Set<String> moddedWhitelist() {
        return MODDED_WHITELIST;
    }

    public static void setLockModded(boolean lockModded) {
        SerDesFilter.lockModded = lockModded;
    }

    public static boolean isLockModded() {
        return lockModded;
    }

    @Override
    public boolean isParallel(TickingBlockEntity ticker) {
        String type = ticker.getType();
        if (BLACKLIST.contains(type)) {
            return false;
        }
        if (lockModded && !type.startsWith("minecraft:")) {
            return MODDED_WHITELIST.contains(type);
        }
        return true;
    }
}