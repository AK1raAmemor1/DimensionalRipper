package dev.modzuozhi.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.modzuozhi.core.dimthread.EntityTickParallel;
import dev.modzuozhi.core.dimthread.ISectionKeyAccessor;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * 第4/5步：实体 tick 并行——{@code EntitySection} 内部存储的并发读写互斥（借鉴 MCMT 思路，写我们自己的实现）。
 * <p>
 * {@code EntitySection.storage} 是 {@code ClassInstanceMultiMap}（内部 {@code HashMap}/{@code ArrayList}，
 * 非线程安全）。并行子任务中：村民大脑传感器（{@code NearestLivingEntitySensor} → {@code getEntitiesOfClass}）
 * 和移动碰撞检测（{@code getEntityCollisions}）会遍历 {@code storage}（{@code getEntities}），
 * 而实体移动（{@code onMove}）与区块加载（{@code addEntityWithoutEvent}）会在任意线程（子任务、维度 worker、
 * 区块加载线程、服务器主线程）增删该 {@code storage}，二者并发即抛 {@code ConcurrentModificationException}，
 * 中断实体 tick 导致实体卡进方块/遁地/窒息。
 * <p>
 * <b>第5步细粒度化：</b>将第4步的全局锁 {@link EntityTickParallel#SECTION_LOCK} 降级为<b>分片锁</b>。
 * 本类注入 {@link #modzuozhi_sectionKey}（由 {@code EntitySectionStorageMixin} 在 createSection 时写入），
 * 对 add/remove/getEntities 用该 key 定位分片锁——不同区域的实体 section 互不阻塞，真正并行。
 * 结构级操作（storage 遍历/创建/删除、manager、callback）仍持全局锁，锁序固定为
 * <b>全局锁 → 分片锁</b>，无反向路径，不会死锁。
 * 用 {@link WrapMethod} 整方法包装，避免 {@code @WrapOperation at HEAD} 对含分支的方法注入失败。
 */
@Mixin(EntitySection.class)
public abstract class EntitySectionMixin implements ISectionKeyAccessor {
    /** 本 section 在 {@code EntitySectionStorage} 中的 key（SectionPos.asLong），由 storage 注入。 */
    @Unique
    private long modzuozhi_sectionKey;

    @Override
    @Unique
    public void modzuozhi_setSectionKey(long key) {
        this.modzuozhi_sectionKey = key;
    }

    @Override
    @Unique
    public long modzuozhi_getSectionKey() {
        return this.modzuozhi_sectionKey;
    }

    @WrapMethod(method = "add")
    private void modzuozhi_lockedAdd(EntityAccess entity, Operation<Void> original) {
        // 内容写入：分片写锁（独占）
        EntityTickParallel.lockSection(this.modzuozhi_sectionKey);
        try {
            original.call(entity);
        } finally {
            EntityTickParallel.unlockSection(this.modzuozhi_sectionKey);
        }
    }

    @WrapMethod(method = "remove")
    private boolean modzuozhi_lockedRemove(EntityAccess entity, Operation<Boolean> original) {
        EntityTickParallel.lockSection(this.modzuozhi_sectionKey);
        try {
            return original.call(entity);
        } finally {
            EntityTickParallel.unlockSection(this.modzuozhi_sectionKey);
        }
    }

    @WrapMethod(method = "getEntities(Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)Lnet/minecraft/util/AbortableIterationConsumer$Continuation;")
    private AbortableIterationConsumer.Continuation modzuozhi_lockedGetEntities(
        AABB aabb, AbortableIterationConsumer<EntityAccess> consumer,
        Operation<AbortableIterationConsumer.Continuation> original
    ) {
        // 内容遍历/查询：分片读锁（共享，传感器/碰撞查询并发执行）
        EntityTickParallel.lockSectionRead(this.modzuozhi_sectionKey);
        try {
            return original.call(aabb, consumer);
        } finally {
            EntityTickParallel.unlockSectionRead(this.modzuozhi_sectionKey);
        }
    }

    @WrapMethod(method = "getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)Lnet/minecraft/util/AbortableIterationConsumer$Continuation;")
    private AbortableIterationConsumer.Continuation modzuozhi_lockedGetEntitiesTyped(
        EntityTypeTest<EntityAccess, ?> typeTest, AABB aabb,
        AbortableIterationConsumer<?> consumer,
        Operation<AbortableIterationConsumer.Continuation> original
    ) {
        EntityTickParallel.lockSectionRead(this.modzuozhi_sectionKey);
        try {
            return original.call(typeTest, aabb, consumer);
        } finally {
            EntityTickParallel.unlockSectionRead(this.modzuozhi_sectionKey);
        }
    }

    /**
     * {@code getEntities()} 返回的 {@code storage.stream()} 是<b>惰性</b>流，锁无法覆盖其消费阶段。
     * 消费方（{@code PersistentEntitySectionManager.updateChunkStatus/storeChunkSections}）可能在子任务
     * 并发增删时迭代底层 {@code ArrayList} 而抛 CME。这里在锁内<b>收集成快照</b>再返回，消费时必然安全。
     */
    @WrapMethod(method = "getEntities()Ljava/util/stream/Stream;")
    private Stream<EntityAccess> modzuozhi_lockedGetEntitiesStream(Operation<Stream<EntityAccess>> original) {
        EntityTickParallel.lockSectionRead(this.modzuozhi_sectionKey);
        try {
            return original.call().collect(Collectors.toList()).stream();
        } finally {
            EntityTickParallel.unlockSectionRead(this.modzuozhi_sectionKey);
        }
    }
}
