package dev.modzuozhi.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 第7步：寻路/AI 并发安全——{@code ServerLevel.navigatingMobs} 并发化（借鉴 MCMT 思路，写我们自己的实现）。
 * <p>
 * {@code navigatingMobs} 记录当前正在寻路的 {@link Mob}，原为 {@code ObjectOpenHashSet}（非线程安全）：
 * <ul>
 *     <li>写入：{@code onTrackingStart/onTrackingEnd}（实体追踪变化时 {@code add}/{@code remove}），
 *         可能来自<b>并行实体子任务线程</b>（实体 tick 触发追踪变化）、维度 worker、区块加载线程；</li>
 *     <li>遍历：{@code sendBlockUpdated}（方块变化时）遍历所有 mob 的 navigation 判断是否重算路径，
 *         同样可能被任意线程触发。</li>
 * </ul>
 * 两者并发会抛 {@code ConcurrentModificationException} 或破坏集合，导致寻路异常/崩溃。
 * <p>
 * 修复：替换为 {@link ConcurrentHashMap#newKeySet()} 背书的并发集合（线程安全、迭代弱一致、
 * 不抛 CME）。原版串行行为不变，零语义影响。
 */
@Mixin(ServerLevel.class)
public abstract class NavigatingMobsMixin {
    @Shadow
    @Final
    @Mutable
    private Set<Mob> navigatingMobs = ConcurrentHashMap.newKeySet();
}
