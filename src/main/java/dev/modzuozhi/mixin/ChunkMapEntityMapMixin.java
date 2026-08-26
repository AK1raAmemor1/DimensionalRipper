package dev.modzuozhi.mixin;

import dev.modzuozhi.core.collection.Int2ObjectConcurrentHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 实体跟踪表（{@code ChunkMap.entityMap}）并发化（借鉴 MCMT/Async 思路，独立实现）。
 * <p>
 * {@code entityMap}（{@code Int2ObjectOpenHashMap<TrackedEntity>}）记录所有被跟踪实体。
 * 维度并行下存在两条<b>不同线程</b>的访问路径：
 * <ul>
 *     <li><b>玩家移动包处理</b>（维度 worker）：{@code handleMovePlayer → ChunkMap.move} 遍历
 *         {@code entityMap.values()} 更新玩家可见实体；</li>
 *     <li><b>实体并行子任务</b>（SUB_POOL）：tick 其它实体时实体生成/死亡/进出视距 →
 *         {@code ChunkMap.addEntity/removeEntity} 并发 {@code put/remove} 同一张表。</li>
 * </ul>
 * fastutil {@code Int2ObjectOpenHashMap} 遍历与修改并发 → 迭代器损坏（{@code wrapped=null}
 * NPE）→ 移动包处理失败刷屏 → 主线程 {@code Can't keep up} → 玩家"连接中断"掉线（实测：
 * 大量女仆/召唤物生成时快速跑图必现）。
 * <p>
 * 修复：用 {@link Int2ObjectConcurrentHashMap}（内部 {@code ConcurrentHashMap}，弱一致迭代）
 * 替换 {@code entityMap} 实例（{@code @Shadow} 初始值替换，与 {@code EntityLookupMixin}/
 * {@code PoiSectionMixin} 相同的已验证模式）。遍历与增删并发不再抛 NPE/CME，纯加固，
 * 不改变任何 tick 归属与调度逻辑。
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapEntityMapMixin {

    @Shadow
    private final Int2ObjectMap<?> entityMap = new Int2ObjectConcurrentHashMap<>();
}
