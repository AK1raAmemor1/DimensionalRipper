package dev.modzuozhi.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 属性修饰符并发化（借鉴 Async 思路，独立实现）。
 * <p>
 * {@code AttributeInstance} 的修饰符表在并行 tick 下被跨线程读写（增益/减益结算、伤害计算、
 * 传感器查询）。原版用普通 Map，替换为并发容器；{@code getModifiers} 返回的按操作分类子表
 * 也并发化，保证 computeIfAbsent 原子且子表写入安全。
 */
@Mixin(AttributeInstance.class)
public abstract class AttributeInstanceMixin {

    @Shadow
    private final Map<AttributeModifier.Operation, Map<ResourceLocation, AttributeModifier>> modifiersByOperation =
            new ConcurrentHashMap<>();

    @Shadow
    private final Map<ResourceLocation, AttributeModifier> modifierById = new ConcurrentHashMap<>();

    @Shadow
    private final Map<ResourceLocation, AttributeModifier> permanentModifiers = new ConcurrentHashMap<>();

    @WrapMethod(method = "getModifiers(Lnet/minecraft/world/entity/ai/attributes/AttributeModifier$Operation;)Ljava/util/Map;")
    private Map<ResourceLocation, AttributeModifier> modzuozhi_getModifiers(
            AttributeModifier.Operation operation, Operation<Map<ResourceLocation, AttributeModifier>> original) {
        return this.modifiersByOperation.computeIfAbsent(operation, op -> new ConcurrentHashMap<>());
    }
}
