package dev.modzuozhi.core.dimthread;

import dev.modzuozhi.ModZuozhi;

/**
 * 第9步后半：故障监控与自动回退（fail-safe / graceful degradation）。
 * <p>
 * 目标：运行期检测到并行故障时<b>安全降级而非崩溃</b>。采用两级回退，先关细粒度、再退回原版串行：
 * <ul>
 *     <li><b>细粒度降级</b>：子任务连续异常达到 {@link #FINE_FAULT_THRESHOLD} 次 → 自动关闭
 *         {@link FineGrainScheduler}（退回纯维度并行）。子任务异常发生在独立子任务池内，不影响
 *         维度 tick 收口，因此关闭是安全的。</li>
 *     <li><b>维度并行回退</b>：维度 tick 连续异常达到 {@link #DIM_FAULT_THRESHOLD} 次，或维度 tick
 *         连续超时（看门狗，{@link #AWAIT_TIMEOUT_MS}）达到 {@link #AWAIT_TIMEOUT_THRESHOLD} 次 →
 *         关闭全局 {@link ModZuozhi#ENABLED}，退回原版串行。</li>
 * </ul>
 * 计数均为<b>连续</b>语义（成功/按时完成会清零），容忍偶发抖动，只有持续故障才触发回退。
 * 手动 {@code /dimensionalripper on|fine on} 重新启用时调用 {@link #reset()} 清除降级状态。
 */
public final class FaultGuard {
    /** 子任务连续异常达到该次数，自动关闭细粒度并行。 */
    private static final int FINE_FAULT_THRESHOLD = 5;
    /** 维度 tick 连续异常达到该次数，自动回退原版串行。 */
    private static final int DIM_FAULT_THRESHOLD = 3;
    /** 维度 tick 连续超时达到该次数，自动回退原版串行。 */
    private static final int AWAIT_TIMEOUT_THRESHOLD = 3;
    /** 看门狗：维度 tick 最晚完成时限（主线程等待上限）。 */
    public static final long AWAIT_TIMEOUT_MS = 30_000L;

    /** 细粒度是否已被自动关闭。 */
    private static volatile boolean fineDegraded = false;
    /** 维度并行是否已被自动回退。 */
    private static volatile boolean dimDegraded = false;
    /** 最近的降级原因（status 命令展示）。 */
    private static volatile String reason = "正常";
    /** 连续失败计数（仅写入线程同步读写，读取方为 status/日志，volatile 已足够）。 */
    private static int fineFailures;
    private static int dimFailures;
    private static int awaitTimeouts;

    private FaultGuard() {
    }

    /** 上报一次子任务异常；达阈值自动关闭细粒度并行。 */
    public static void onSubTaskFailure() {
        if (fineDegraded) {
            return;
        }
        fineFailures++;
        if (fineFailures >= FINE_FAULT_THRESHOLD) {
            fineDegraded = true;
            reason = "子任务连续异常 " + fineFailures + " 次，已自动关闭细粒度并行（保留维度并行）";
            ModZuozhi.LOGGER.error("[FaultGuard] {}", reason);
            FineGrainScheduler.setEnabled(false);
        }
    }

    /** 上报维度 tick 异常；达阈值自动回退原版串行。 */
    public static void onDimTickFailure(Throwable t) {
        if (dimDegraded) {
            return;
        }
        dimFailures++;
        ModZuozhi.LOGGER.error("[FaultGuard] 维度 tick 异常（第 {} 次）", dimFailures, t);
        if (dimFailures >= DIM_FAULT_THRESHOLD) {
            dimDegraded = true;
            reason = "维度 tick 连续异常 " + dimFailures + " 次，已自动回退原版串行";
            ModZuozhi.LOGGER.error("[FaultGuard] {}", reason);
            // 关闭顺序与 setMaster 一致：先关细粒度并排空残留子任务（此时 ENABLED 仍 true，
            // section 锁与碰撞拦截仍生效，残留任务安全跑完），再关全局，最后熄灭维度 loop
            // （否则主线程退回原版串行 tick 时仍会经 mixin 提交并行子任务 → 双 tick/CME 竞态）。
            FineGrainScheduler.setEnabled(false);
            FineGrainScheduler.awaitIdle(5000L);
            ModZuozhi.ENABLED = false;
            DimThreadCore.MANAGER.killAllLoops();
        }
    }

    /** 上报子任务屏障等待超时（子任务卡死/死锁）：立即降级，避免维度 worker 永久阻塞导致整服冻结。 */
    public static void onBarrierTimeout() {
        if (dimDegraded) {
            return;
        }
        dimDegraded = true;
        reason = "子任务屏障等待超时（疑似死锁），已自动回退原版串行";
        ModZuozhi.LOGGER.error("[FaultGuard] {}", reason);
        // 与 onDimTickFailure 一致：先关细粒度并尽力排空残留子任务，再关全局，
        // 最后熄灭维度 loop，释放可能被卡死子任务持有的锁，恢复主线程原版串行。
        FineGrainScheduler.setEnabled(false);
        FineGrainScheduler.awaitIdle(2000L);
        ModZuozhi.ENABLED = false;
        DimThreadCore.MANAGER.killAllLoops();
    }

    /**
     * 看门狗：主线程等待维度 tick 完成后上报结果。
     *
     * @param done 是否在 {@link #AWAIT_TIMEOUT_MS} 内完成
     * @return 本次是否触发维度并行回退（调用方需在返回 true 时等待残留 worker 收口）
     */
    public static boolean onAwaitCompleted(boolean done) {
        if (dimDegraded) {
            return false;
        }
        if (!done) {
            awaitTimeouts++;
            ModZuozhi.LOGGER.error("[FaultGuard] 维度 tick 超时（第 {} 次，>{}/tick）", awaitTimeouts, AWAIT_TIMEOUT_MS);
            if (awaitTimeouts >= AWAIT_TIMEOUT_THRESHOLD) {
                dimDegraded = true;
                reason = "维度 tick 连续超时 " + awaitTimeouts + " 次，已自动回退原版串行";
                ModZuozhi.LOGGER.error("[FaultGuard] {}", reason);
                // 与 onDimTickFailure 一致：先关细粒度并排空残留子任务，再关全局，
                // 避免主线程串行 tick 时仍提交并行子任务（双 tick / section 无锁遍历 CME 竞态）。
                FineGrainScheduler.setEnabled(false);
                FineGrainScheduler.awaitIdle(5000L);
                ModZuozhi.ENABLED = false;
                return true;
            }
        } else {
            // 按时完成：重置连续超时计数
            awaitTimeouts = 0;
        }
        return false;
    }

    public static boolean isFineDegraded() {
        return fineDegraded;
    }

    public static boolean isDimDegraded() {
        return dimDegraded;
    }

    public static String getReason() {
        return reason;
    }

    /** 手动重新启用（/dimensionalripper on|fine on）时重置所有降级状态与计数。 */
    public static void reset() {
        fineDegraded = false;
        dimDegraded = false;
        reason = "正常";
        fineFailures = 0;
        dimFailures = 0;
        awaitTimeouts = 0;
        ModZuozhi.LOGGER.info("[FaultGuard] 降级状态已重置");
    }
}
