package dev.modzuozhi.mixin;

import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 属性表并发化（借鉴 Async 思路，独立实现）。
 * <p>
 * 并行 tick 下，传感器/寻路/目标选择会跨线程读取<b>其它实体</b>的属性
 * （{@code getAttribute → getInstance}、{@code getValue}），属性被修改时还会写入
 * {@code attributesToUpdate/attributesToSync}。原版 {@code AttributeMap} 用普通 Map/Set，
 * 跨线程读写存在数据竞争。全部替换为并发容器，读写安全。
 */
@Mixin(AttributeMap.class)
public abstract class AttributeMapMixin {

    @Shadow
    private final Map<Holder<Attribute>, AttributeInstance> attributes = new ConcurrentHashMap<>();

    @Shadow
    private final Set<AttributeInstance> attributesToSync = ConcurrentHashMap.newKeySet();

    @Shadow
    private final Set<AttributeInstance> attributesToUpdate = ConcurrentHashMap.newKeySet();
}
