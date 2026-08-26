package dev.modzuozhi.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.GoToPotentialJobSite;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 跨维度安全：{@link GoToPotentialJobSite} 添加维度检查。
 * <p>
 * 原版 {@code checkExtraStartConditions}（1.21.1）只检查 {@code Activity.WORK}，不做维度检查。
 * 当村民的 {@code POTENTIAL_JOB_SITE} 存了另一个维度的 {@code GlobalPos} 时，村民会启动行为
 * 并尝试在当前维度坐标下寻路到该位置（坐标语义错乱）。并行环境下（维度并行/细粒度并行）也需
 * 确保村民只寻路当前维度的 POI。
 * <p>
 * 在 {@code checkExtraStartConditions} 头部注入守卫：POTENTIAL_JOB_SITE 维度不匹配时直接返回
 * false，阻止行为启动（不覆盖原版逻辑，仅在维度不匹配时短路）。
 */
@Mixin(GoToPotentialJobSite.class)
public class GoToPotentialJobSiteGuardMixin {

    @Inject(method = "checkExtraStartConditions(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/npc/Villager;)Z",
            at = @At("HEAD"), cancellable = true)
    private void modzuozhi_guardDimension(
        ServerLevel serverLevel, Villager villager, CallbackInfoReturnable<Boolean> cir
    ) {
        if (villager.getBrain().getMemory(MemoryModuleType.POTENTIAL_JOB_SITE)
                .map(gp -> gp.dimension() != villager.level().dimension())
                .orElse(false)) {
            cir.setReturnValue(false);
        }
    }
}