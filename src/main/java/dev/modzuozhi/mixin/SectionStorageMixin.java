package dev.modzuozhi.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Optional;

/**
 * POI 区块存储表线程安全化（村民 POI 并发访问加固）。
 * <p>
 * {@code SectionStorage} 是 {@code PoiManager} 的基类，其内部 {@code storage}（
 * {@code Long2ObjectOpenHashMap}）在细粒度并行下会被<b>子任务线程</b>（村民传感器查询 POI →
 * {@code getOrLoad}）与<b>区块加载线程</b>（{@code readColumn → storage.put}）并发读写。
 * fastutil 的普通哈希表并发 put 会在 rehash 时损坏（实测抛
 * {@code ArrayIndexOutOfBoundsException: Index -1 out of bounds ... rehash}），
 * 进而引发 chunk 加载失败、连锁异常。
 * <p>
 * 修复：把 {@code storage} 替换为 {@link Long2ObjectMaps#synchronize 同步包装}，所有
 * get/put/remove/values 访问线程安全，从根上消除该数据竞争。
 */
@Mixin(SectionStorage.class)
public abstract class SectionStorageMixin<R> {

    @Shadow
    private final Long2ObjectMap<Optional<R>> storage =
            Long2ObjectMaps.synchronize(new Long2ObjectOpenHashMap<>());
}
