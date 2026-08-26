package dev.modzuozhi.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.entity.ai.behavior.YieldJobSite;
import net.minecraft.world.entity.ai.behavior.declarative.BehaviorBuilder;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Optional;

/**
 * 跨维度安全：{@link YieldJobSite} 添加维度检查。
 * <p>
 * 原版 {@code YieldJobSite.create()} 不做维度检查，当 {@code POTENTIAL_JOB_SITE} 存了
 * 另一个维度的 {@code GlobalPos} 时，村民会跨维度让工作站。并行环境下需确保 AI 只在本维度
 * 操作，避免跨维度引用导致的不一致。
 * <p>
 * 借鉴 Chlorophyll（Unlicense）的 {@code YieldJobSiteMixin} 思路，增加
 * {@code globalPos.dimension() != villager.level().dimension()} 检查，维度不匹配时直接返回。
 */
@Mixin(YieldJobSite.class)
public abstract class YieldJobSiteGuardMixin {

    @Shadow
    private static boolean nearbyWantsJobsite(Holder<PoiType> holder, Villager villager, BlockPos pos) {
        throw new AssertionError();
    }

    /**
     * @reason 添加维度守卫，防止跨维度让工作站
     */
    @Overwrite
    public static BehaviorControl<Villager> create(float f) {
        return BehaviorBuilder.create((instance) ->
            instance.group(
                instance.present(MemoryModuleType.POTENTIAL_JOB_SITE),
                instance.absent(MemoryModuleType.JOB_SITE),
                instance.present(MemoryModuleType.NEAREST_LIVING_ENTITIES),
                instance.registered(MemoryModuleType.WALK_TARGET),
                instance.registered(MemoryModuleType.LOOK_TARGET)
            ).apply(instance, (memoryAccessor, memoryAccessor2, memoryAccessor3, memoryAccessor4, memoryAccessor5) ->
                (serverLevel, villager, l) -> {
                    if (villager.isBaby()) {
                        return false;
                    }
                    if (villager.getVillagerData().getProfession() != VillagerProfession.NONE) {
                        return false;
                    }
                    GlobalPos globalPos = instance.get(memoryAccessor);
                    // 维度守卫：潜在工作站不在当前维度则跳过
                    if (globalPos.dimension() != villager.level().dimension()) {
                        return true;
                    }
                    BlockPos blockPos = globalPos.pos();
                    Optional<Holder<PoiType>> optional = serverLevel.getPoiManager().getType(blockPos);

                    optional.flatMap(poiTypeHolder ->
                        instance.get(memoryAccessor3).stream()
                            .filter((livingEntity) -> livingEntity instanceof Villager && livingEntity != villager)
                            .map((livingEntity) -> (Villager) livingEntity)
                            .filter(LivingEntity::isAlive)
                            .filter((villagerx) -> nearbyWantsJobsite(poiTypeHolder, villagerx, blockPos))
                            .findFirst()
                    ).ifPresent((villagerx) -> {
                        memoryAccessor4.erase();
                        memoryAccessor5.erase();
                        memoryAccessor.erase();
                        if (villagerx.getBrain().getMemory(MemoryModuleType.JOB_SITE).isEmpty()) {
                            BehaviorUtils.setWalkAndLookTargetMemories(villagerx, blockPos, f, 1);
                            villagerx.getBrain().setMemory(MemoryModuleType.POTENTIAL_JOB_SITE, GlobalPos.of(serverLevel.dimension(), blockPos));
                        }
                    });

                    return true;
                }
            )
        );
    }
}