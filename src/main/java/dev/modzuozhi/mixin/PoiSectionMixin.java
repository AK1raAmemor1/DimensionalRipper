package dev.modzuozhi.mixin;

import com.google.common.collect.Maps;
import it.unimi.dsi.fastutil.shorts.Short2ObjectMap;
import it.unimi.dsi.fastutil.shorts.Short2ObjectMaps;
import it.unimi.dsi.fastutil.shorts.Short2ObjectOpenHashMap;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiSection;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;
import java.util.Set;

/**
 * POI 区块内部表线程安全化（村民 POI 并发访问加固）。
 * <p>
 * 并行 tick 下，村民传感器（找床/找工作点）在子任务线程遍历 {@code PoiSection} 的
 * {@code records}/{@code byType}，而方块变化会在 worker 线程触发 {@code add/remove} 修改同一
 * 结构。普通 HashMap/fastutil 哈希表并发读写存在数据竞争（迭代时被修改 → CME 或撕裂读）。
 * <p>
 * 修复：{@code records} 换成 {@link Short2ObjectMaps#synchronize 同步包装}，
 * {@code byType} 换成 {@link ConcurrentHashMap}（弱一致迭代安全）。
 */
@Mixin(PoiSection.class)
public abstract class PoiSectionMixin {

    @Shadow
    private final Short2ObjectMap<PoiRecord> records =
            Short2ObjectMaps.synchronize(new Short2ObjectOpenHashMap<>());

    @Shadow
    private final Map<Holder<PoiType>, Set<PoiRecord>> byType = Maps.newConcurrentMap();
}
