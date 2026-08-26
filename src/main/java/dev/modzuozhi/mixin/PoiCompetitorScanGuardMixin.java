package dev.modzuozhi.mixin;

import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.PoiCompetitorScan;
import net.minecraft.world.entity.ai.behavior.declarative.BehaviorBuilder;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 跨维度安全：{@link PoiCompetitorScan} 添加维度检查。
 * <p>
 * 原版 {@code PoiCompetitorScan.create()} 不做维度检查，当村民的 {@code JOB_SITE} 存了
 * 另一个维度的 {@code GlobalPos} 时，会跨维度访问 POI 管理器。并行环境下（维度线程/子任务线程）
 * 跨维度访问虽已由 POI 数据结构加固保障安全，但 AI 逻辑本身不应跨维度竞争工作站。
 * <p>
 * 借鉴 Chlorophyll（Unlicense）的 {@code PoiCompetitorScanMixin} 思路，在竞争逻辑前
 * 增加 {@code globalPos.dimension() != villager.level().dimension()} 检查，维度不匹配时
 * 直接跳过（不参与竞争）。
 */
@Mixin(PoiCompetitorScan.class)
public abstract class PoiCompetitorScanGuardMixin {

    @Shadow
    private static boolean competesForSameJobsite(GlobalPos globalPos, Holder<PoiType> holder, Villager villager) {
        throw new AssertionError();
    }

    @Shadow
    private static Villager selectWinner(Villager villager, Villager villager2) {
        throw new AssertionError();
    }

    /**
     * @reason 添加维度守卫，防止跨维度竞争工作站
     */
    @Overwrite
    public static BehaviorControl<Villager> create() {
        return BehaviorBuilder.create((instance) ->
            instance.group(
                instance.present(MemoryModuleType.JOB_SITE),
                instance.present(MemoryModuleType.NEAREST_LIVING_ENTITIES)
            ).apply(instance, (memoryAccessor, memoryAccessor2) ->
                (serverLevel, villager, l) -> {
                    GlobalPos globalPos = instance.get(memoryAccessor);
                    // 维度守卫：工作站不在当前维度则不参与竞争
                    if (globalPos.dimension() != villager.level().dimension()) {
                        return true;
                    }
                    serverLevel.getPoiManager().getType(globalPos.pos()).ifPresent((holder) ->
                        instance.get(memoryAccessor2).stream()
                            .filter((livingEntity) -> livingEntity instanceof Villager && livingEntity != villager)
                            .map(entity -> (Villager) entity)
                            .filter(LivingEntity::isAlive)
                            .filter((villagerx) -> competesForSameJobsite(globalPos, holder, villagerx))
                            .reduce(villager, (v1, v2) -> selectWinner(v1, v2))
                    );
                    return true;
                }
            )
        );
    }
}