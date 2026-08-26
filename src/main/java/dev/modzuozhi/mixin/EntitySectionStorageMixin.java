package dev.modzuozhi.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.modzuozhi.core.collection.ConcurrentLong2ObjectMap;
import dev.modzuozhi.core.collection.ConcurrentLongSortedSet;
import dev.modzuozhi.core.dimthread.ISectionKeyAccessor;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongSortedSet;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 第4/11步：实体 tick 并行——{@code EntitySectionStorage} 结构存储并发化（借鉴 MCMT 思路，写我们自己的实现）。
 * <p>
 * <b>第11步（SECTION_LOCK → 并发集合）</b>：把 {@code EntitySectionStorage.sections}
 * （{@code Long2ObjectOpenHashMap}）与 {@code sectionIds}（{@code LongAVLTreeSet}）替换为
 * {@link ConcurrentLong2ObjectMap}/{@link ConcurrentLongSortedSet}（弱一致、无 CME）。
 * 由此<b>删除结构级读路径的全部全局读锁</b>：
 * <ul>
 *     <li>读：{@code forEachAccessibleNonEmptySection} 的 {@code sectionIds.subSet().iterator()} +
 *         {@code sections.get} 现在无锁安全（原版源码对 {@code sections.get} 已有 null 检查，
 *         并发创建 section 时读到 null 天然跳过）；</li>
 *     <li>写：{@code getOrCreateSection → computeIfAbsent} 原子、{@code createSection} 内部
 *         {@code sectionIds.add} 与 {@code sections.put} 由 {@code ConcurrentHashMap.computeIfAbsent}
 *         串行化，不会重复建 section。</li>
 * </ul>
 * 结构<b>写</b>（实体生命周期 {@code onMove/onRemove/addEntityWithoutEvent}）仍由
 * {@link dev.modzuozhi.core.dimthread.EntityTickParallel#SECTION_LOCK} 全局写锁保护（本步不触碰），
 * 因此实体移动与 section 内容（分片锁）的既有锁序不变。
 * <p>
 * 用 {@link WrapMethod} 整方法包装，避免 {@code @WrapOperation at HEAD} 对含分支的方法注入失败。
 * <p>
 * <b>第5步细粒度化：</b>在 {@code createSection} 中把 section key 写入新创建的
 * {@code EntitySection}（见 {@link ISectionKeyAccessor}），供 {@code EntitySection} 的分片锁定位锁片。
 */
@Mixin(EntitySectionStorage.class)
public abstract class EntitySectionStorageMixin<T extends EntityAccess> {

    /** 结构存储：并发的 {@code Long2ObjectMap}（替换原 {@code Long2ObjectOpenHashMap}，读路径无锁安全）。 */
    @Shadow
    private final Long2ObjectMap<EntitySection<T>> sections = new ConcurrentLong2ObjectMap<>();

    /** 有序 section key 集：并发的 {@code LongSortedSet}（替换原 {@code LongAVLTreeSet}，subSet 弱一致）。 */
    @Shadow
    private final LongSortedSet sectionIds = new ConcurrentLongSortedSet();

    /**
     * {@code createSection(long)} 是私有方法，创建 {@code EntitySection} 时把 section key 注入
     * 给新 section，使 {@code EntitySection} 的 add/remove/getEntities 能用分片锁定位锁片。
     */
    @WrapMethod(method = "createSection")
    private EntitySection modzuozhi_createSection(long sectionKey, Operation<EntitySection> original) {
        EntitySection section = original.call(sectionKey);
        ((ISectionKeyAccessor) (Object) section).modzuozhi_setSectionKey(sectionKey);
        return section;
    }
}
