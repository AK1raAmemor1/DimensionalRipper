package dev.modzuozhi.mixin;

import dev.modzuozhi.core.collection.ConcurrentLong2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.entity.Visibility;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 第14步（对齐 Async 零锁路线）：{@code PersistentEntitySectionManager} 共享状态并发化。
 * <p>
 * 第4步起，实体生命周期写入（{@code addEntityWithoutEvent}/{@code onMove}/{@code onRemove}）由全局写锁
 * {@code SECTION_LOCK} 串行化；第14步移除该全局写锁，改由并发集合承载，使实体移动/生成可真正并行：
 * <ul>
 *     <li>{@code knownUuids}（普通 {@code HashSet}）→ 并发 {@code Set}（{@code ConcurrentHashMap} 视图），
 *         保护 {@code addEntityUuid}/{@code isLoaded}/{@code onRemove} 的并发读写；</li>
 *     <li>{@code chunkVisibility}（{@code Long2ObjectOpenHashMap}）→ {@link ConcurrentLong2ObjectMap}，
 *         保护 {@code createSection}/{@code updateStatus}/{@code canPositionTick} 对区块可见性的并发读
 *         （写主要在服务器线程 {@code updateChunkStatus}）。</li>
 * </ul>
 * 用 {@code @Shadow} 初始值替换模式（与 {@code PoiSectionMixin}/{@code SectionStorageMixin} 相同，无需 AT）。
 */
@Mixin(PersistentEntitySectionManager.class)
public abstract class EntitySectionManagerMixin<T extends EntityAccess> {

    /** 已注册实体 UUID 集合：并发的 {@code Set}（替换原 {@code HashSet}，读写线程安全）。 */
    @Shadow
    final Set<UUID> knownUuids = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** 区块可见性表：并发的 {@code Long2ObjectMap}（替换原 {@code Long2ObjectOpenHashMap}，读线程安全）。 */
    @Shadow
    private final Long2ObjectMap<Visibility> chunkVisibility = new ConcurrentLong2ObjectMap<>();
}
