package dev.modzuozhi.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.sensing.NearestLivingEntitySensor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 第10步：{@code NearestLivingEntitySensor} 排序快照化，消除并发 TimSort 异常。
 * <p>
 * 原版 {@code doTick} 里 {@code list.sort(Comparator.comparingDouble(entity::distanceToSqr))}
 * 的比较器会<b>实时</b>读取被排序实体的可变坐标。并行 tick 下，其它子任务线程正同时移动这些
 * 实体，导致比较器前后不一致（破坏传递性）→ {@code TimSort} 抛
 * {@code IllegalArgumentException: Comparison method violates its general contract}（日志已证实）。
 * <p>
 * 修复：排序前先对每个实体快照一次到锚点（被 tick 实体）的距离，按<b>快照距离</b>稳定排序并
 * 回写——比较器全程一致，既保留"按距离排序"语义，又不触发 TimSort 异常。
 * <p>
 * 线程安全：每个 {@code NearestLivingEntitySensor} 实例属于单个实体的大脑，仅被该实体自己的
 * 子任务线程调用，字段无跨线程竞争。
 */
@Mixin(NearestLivingEntitySensor.class)
public abstract class NearestLivingEntitySensorMixin {
    /** 当前 tick 的锚点（被 tick 的实体）。 */
    @Unique
    private LivingEntity modzuozhi_anchor;

    @Inject(method = "doTick", at = @At("HEAD"))
    private void modzuozhi_captureAnchor(ServerLevel level, LivingEntity entity, CallbackInfo ci) {
        this.modzuozhi_anchor = entity;
    }

    @Redirect(method = "doTick",
            at = @At(value = "INVOKE", target = "Ljava/util/List;sort(Ljava/util/Comparator;)V"))
    private void modzuozhi_snapshotSort(List<LivingEntity> list, Comparator<LivingEntity> comparator) {
        LivingEntity anchor = this.modzuozhi_anchor;
        if (anchor == null) {
            // 兜底：正常不会触发
            list.sort(comparator);
            this.modzuozhi_anchor = null;
            return;
        }
        int n = list.size();
        double[] dist = new double[n];
        for (int i = 0; i < n; i++) {
            dist[i] = list.get(i).distanceToSqr(anchor);
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        // 快照距离固定 → 比较器一致 → 不会抛 TimSort 异常（稳定排序，等距保持原顺序）
        Arrays.sort(order, (a, b) -> Double.compare(dist[a], dist[b]));
        List<LivingEntity> sorted = new ArrayList<>(n);
        for (int index : order) {
            sorted.add(list.get(index));
        }
        list.clear();
        list.addAll(sorted);
        this.modzuozhi_anchor = null;
    }
}
