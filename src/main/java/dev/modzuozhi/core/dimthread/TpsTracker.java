package dev.modzuozhi.core.dimthread;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务器 TPS 估算（供 {@code /dimensionalripper status} 展示，验证优化效果）。
 * <p>
 * 维度独立 tick 循环改造后，主线程退化为「协调者」，恒按 20 拍运行；真实负载体现在每个维度 loop
 * 的<b>单次 tick 实际耗时（MSPT）</b>上。因此这里按维度记录每次 {@code internalTick} 的真实耗时，
 * {@code TPS = min(20, 1e9 / 平均MSPT)}：
 * <ul>
 *     <li>空闲时 MSPT 远小于 50ms → TPS = 20；</li>
 *     <li>某维度单 tick 超过 50ms 预算 → TPS &lt; 20（真实掉 TPS）。</li>
 * </ul>
 * 这比「记录相邻 tick 的墙钟间隔」更准确——后者会把自排程定时器的唤醒延迟（每拍约 0.5~0.8ms）
 * 一并计入，导致完全空闲时仍显示 19.7。
 * <p>
 * 主线程样本 {@link #tick()} / {@link #tps()} 仅在维度并行关闭（退回原版串行）时有对比意义，
 * 此时主线程间隔即真实 tick 周期。
 */
public final class TpsTracker {
    /** 采样窗口（tick 数）。 */
    private static final int WINDOW = 100;

    /** 主线程最近 N 个 tick 间隔（纳秒）。 */
    private static final long[] INTERVALS = new long[WINDOW];
    private static int index;
    private static boolean primed;
    private static long lastTick = -1L;

    /** 各维度的独立 MSPT 采样（key 为维度，如 {@code minecraft:overworld}）。 */
    private static final Map<ResourceKey<Level>, Sample> DIMENSIONS = new ConcurrentHashMap<>();

    private TpsTracker() {
    }

    /** 服务器主线程每 tick 调用一次。 */
    public static void tick() {
        long now = System.nanoTime();
        if (lastTick >= 0L) {
            long interval = now - lastTick;
            synchronized (TpsTracker.class) {
                INTERVALS[index] = interval;
                index = (index + 1) % WINDOW;
                primed = true;
            }
        }
        lastTick = now;
    }

    /** 某维度 loop 每次完成 {@code internalTick} 后调用，上报本次真实 tick 耗时（纳秒）。 */
    public static void recordDimension(ResourceKey<Level> dimension, long tickNanos) {
        Sample sample = DIMENSIONS.computeIfAbsent(dimension, k -> new Sample());
        sample.record(tickNanos);
    }

    /** 主线程 (协调者) TPS。数据不足时返回 20。 */
    public static double tps() {
        synchronized (TpsTracker.class) {
            if (!primed) {
                return 20.0;
            }
            long sum = 0L;
            for (long interval : INTERVALS) {
                if (interval > 0L) {
                    sum += interval;
                }
            }
            return sum <= 0L ? 20.0 : tpsFromAvg(sum / (double) WINDOW);
        }
    }

    /** 指定维度的真实 TPS。无采样时返回 20。 */
    public static double tps(ResourceKey<Level> dimension) {
        Sample sample = DIMENSIONS.get(dimension);
        return sample == null ? 20.0 : sample.tps();
    }

    /** 按维度名排序返回每维度的 TPS，供 {@code /dimensionalripper status} 展示。 */
    public static Map<String, Double> allDimensionTps() {
        Map<String, Double> result = new TreeMap<>();
        for (Map.Entry<ResourceKey<Level>, Sample> entry : DIMENSIONS.entrySet()) {
            result.put(entry.getKey().location().toString(), entry.getValue().tps());
        }
        return result;
    }

    private static double tpsFromAvg(double avgNanos) {
        if (avgNanos <= 0.0) {
            return 20.0;
        }
        return Math.min(20.0, 1_000_000_000.0 / avgNanos);
    }

    /** 单个维度的独立 MSPT 采样窗口。只在所属维度 loop 线程写入，读走 {@code synchronized}。 */
    private static final class Sample {
        private final long[] work = new long[WINDOW];
        private int index;
        private int count;

        synchronized void record(long nanos) {
            work[index] = nanos;
            index = (index + 1) % WINDOW;
            if (count < WINDOW) {
                count++;
            }
        }

        synchronized double tps() {
            if (count == 0) {
                return 20.0;
            }
            long sum = 0L;
            for (int i = 0; i < count; i++) {
                sum += work[i];
            }
            return tpsFromAvg(sum / (double) count);
        }
    }
}