package dev.modzuozhi.mixin;

import dev.modzuozhi.core.collection.ConcurrentLong2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
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
 * 旧修复：把 {@code storage} 替换为 {@code Long2ObjectMaps.synchronize} 同步包装，数据安全了，
 * 但<b>全表互斥</b>把村民 POI 查询（读多写少、原本可并行）串行化：19000 只村民压测下
 * 19 个子任务线程 16 个 BLOCKED 在同一把锁上，成为并行 tick 的串行瓶颈（TPS 9 / CPU 半闲）。
 * <p>
 * 新修复：换成 {@link ConcurrentLong2ObjectMap}（{@code ConcurrentHashMap} 底座，
 * {@code SectionStorage} 对 storage 的全部调用 get/put/remove/size 均已覆盖）——
 * 读路径无锁并行，写路径（区块加载 put、方块变化）分段安全，弱一致迭代兼容
 * {@code writeColumn} 的读取。POI 查询恢复并行，消除该锁地板。
 */
@Mixin(SectionStorage.class)
public abstract class SectionStorageMixin<R> {

    @Shadow
    private final Long2ObjectMap<Optional<R>> storage =
            new ConcurrentLong2ObjectMap<>();
}
