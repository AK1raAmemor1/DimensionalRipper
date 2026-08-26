package dev.modzuozhi.mixin;

import dev.modzuozhi.core.collection.Int2ObjectConcurrentHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityLookup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 第14步（对齐 Async 零锁路线）：{@code EntityLookup}（可见实体表）并发化。
 * <p>
 * {@code EntityLookup} 是 {@code PersistentEntitySectionManager.visibleEntityStorage}：
 * <ul>
 *     <li>{@code byId}（{@code Int2ObjectLinkedOpenHashMap}）→ {@link Int2ObjectConcurrentHashMap}
 *         （内部 {@code ConcurrentHashMap}，弱一致迭代）；</li>
 *     <li>{@code byUuid}（普通 {@code HashMap}）→ {@link ConcurrentHashMap}。</li>
 * </ul>
 * 实体并行 tick 下，{@code startTracking/stopTracking}（实体可见性变更，经 onMove/updateStatus/
 * addEntityWithoutEvent 触发）会从<b>多个并行子任务线程</b>并发读写该表。此前由全局写锁
 * {@code SECTION_LOCK} 串行化；第14步移除全局写锁后，改由并发集合承载，读写均线程安全。
 * {@code getEntities/getAllEntities} 遍历 {@code byId.values()}（弱一致），并发增删不抛 CME。
 * 用 {@code @Shadow} 初始值替换模式换成并发实例（与 {@code PoiSectionMixin}/{@code SectionStorageMixin}
 * 相同的已验证模式，无需 AT）。
 */
@Mixin(EntityLookup.class)
public abstract class EntityLookupMixin<T extends EntityAccess> {

    @Shadow
    private final Int2ObjectMap<T> byId = new Int2ObjectConcurrentHashMap<>();

    @Shadow
    private final Map<UUID, T> byUuid = new ConcurrentHashMap<>();
}
