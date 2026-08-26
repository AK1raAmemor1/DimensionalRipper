package dev.modzuozhi.mixin;

import net.minecraft.world.entity.ai.gossip.GossipContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 流言表并发化（借鉴 Async 思路，独立实现）。
 * <p>
 * 并行 tick 下，村民之间交换流言（{@code transferFrom}）或读取声望时会跨线程读写彼此的
 * {@code GossipContainer.gossips}（如判断是否信任某个玩家、村民间声望值）。原版用普通
 * HashMap，跨线程读写存在数据竞争。替换为 {@link ConcurrentHashMap}，读写均线程安全。
 */
@Mixin(GossipContainer.class)
public abstract class GossipContainerMixin {

    @Shadow
    private final Map<UUID, ?> gossips = new ConcurrentHashMap<>();
}
