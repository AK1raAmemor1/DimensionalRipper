package dev.modzuozhi.mixin;

import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.BabyFollowAdult;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.behavior.OneShot;
import net.minecraft.world.entity.ai.behavior.declarative.BehaviorBuilder;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.util.function.Function;

/**
 * 跨维度安全：{@link BabyFollowAdult} 添加维度检查。
 * <p>
 * 原版 1.21.1 的 {@code create()} 不做维度检查，当幼年生物追踪的成年目标在另一个维度时，
 * 幼年生物会尝试跨维度寻路。并行环境下需确保幼年生物只跟随同一维度的目标。
 * <p>
 * 直接覆盖 1.21.1 实际存在的 {@code create(UniformInt, Function)} 重载（此为底层实现，
 * {@code create(UniformInt, float)} 委托给此方法），增加维度检查。
 */
@Mixin(BabyFollowAdult.class)
public class BabyFollowAdultGuardMixin {

    /**
     * @reason 添加维度守卫，防止幼年生物跨维度跟随成年目标
     */
    @Overwrite
    public static @NotNull OneShot<AgeableMob> create(
        UniformInt uniformInt,
        Function<LivingEntity, Float> function
    ) {
        return BehaviorBuilder.create((instance) ->
            instance.group(
                instance.present(MemoryModuleType.NEAREST_VISIBLE_ADULT),
                instance.registered(MemoryModuleType.LOOK_TARGET),
                instance.absent(MemoryModuleType.WALK_TARGET)
            ).apply(instance, (memoryAccessor, memoryAccessor2, memoryAccessor3) ->
                (serverLevel, livingEntity, l) -> {
                    if (!livingEntity.isBaby()) {
                        return false;
                    }
                    LivingEntity livingEntity2 = instance.get(memoryAccessor);

                    // 维度守卫：成年目标不在同一维度则清除记忆并跳过
                    if (livingEntity2.level() != livingEntity.level()) {
                        memoryAccessor.erase();
                        return true;
                    }

                    if (livingEntity.closerThan(livingEntity2,
                            (double) (uniformInt.getMaxValue() + 1))
                        && !livingEntity.closerThan(livingEntity2,
                            (double) uniformInt.getMinValue())) {
                        WalkTarget walkTarget = new WalkTarget(
                            new EntityTracker(livingEntity2, false),
                            function.apply(livingEntity),
                            uniformInt.getMinValue() - 1
                        );
                        memoryAccessor2.set(new EntityTracker(livingEntity2, true));
                        memoryAccessor3.set(walkTarget);
                        return true;
                    }
                    return false;
                }
            )
        );
    }
}