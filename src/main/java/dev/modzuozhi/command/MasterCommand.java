package dev.modzuozhi.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import dev.modzuozhi.ModZuozhi;
import dev.modzuozhi.core.dimthread.DimThreadCore;
import dev.modzuozhi.core.dimthread.FaultGuard;
import dev.modzuozhi.core.dimthread.FineGrainScheduler;
import dev.modzuozhi.core.dimthread.TpsTracker;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import static net.minecraft.commands.Commands.literal;

/**
 * modzuozhi 管理命令。
 * <p>
 * 全局多线程优化<b>默认启用</b>，不提供全局开关（运行时切换全局并行风险高，容易引发
 * 状态不一致问题）。若触发自动降级（见 {@link FaultGuard}），默认退回原版串行，重启游戏
 * 即恢复启用，也可执行 {@code /dimensionalripper fine on} 手动复位。
 * <p>
 * /dimensionalripper fine on|off - 单独开关"细粒度并行"（维度内 TE/区块/实体并行，默认开启）。
 * /dimensionalripper nocollide on|off - 单独开关"禁用实体间碰撞"（Mob Collision Off 模式，默认开启）。
 * /dimensionalripper status - 查看当前运行状态、降级情况与故障原因。
 */
public final class MasterCommand {
    private MasterCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                literal("dimensionalripper")
                        .then(literal("status").executes(MasterCommand::status))
                        .then(literal("fine")
                                .then(literal("on").executes(ctx -> setFine(ctx, true)))
                                .then(literal("off").executes(ctx -> setFine(ctx, false))))
                        .then(literal("nocollide")
                                .then(literal("on").executes(ctx -> setNoCollide(ctx, true)))
                                .then(literal("off").executes(ctx -> setNoCollide(ctx, false)))));
    }

    private static int setFine(CommandContext<CommandSourceStack> ctx, boolean enabled) {
        if (enabled && !ModZuozhi.isEnabled()) {
            // 全局关闭时单独开细粒度没有意义（维度 loop 已停、并行入口被 isActive 拦截），
            // 还会留下"全局关但细粒度开"的不一致状态。这里自动恢复全局开启，下一 tick
            // 主线程会重新拉起维度 loop（killAllLoops 已清空 loops map，computeIfAbsent 重建）。
            ModZuozhi.ENABLED = true;
            ctx.getSource().sendSuccess(() ->
                    Component.literal("全局此前已关闭，已随细粒度一并恢复为开启"), false);
            ModZuozhi.LOGGER.info("[ModZuozhi] 全局随 /dimensionalripper fine on 自动恢复为开启");
        }
        FineGrainScheduler.setEnabled(enabled);
        if (!enabled) {
            // 关闭细粒度后必须先排空 SUB_POOL 残留子任务：此时维度 loop 已退化为串行 tick，
            // 残留实体子任务会与它并发改 EntitySection（靠 isLockDisabled 的残留保护仍持锁互斥，
            // 见 EntityTickParallel），等排空后锁才回落到零开销纯串行。
            FineGrainScheduler.awaitIdle(5000L);
        }
        if (enabled) {
            FaultGuard.reset();
        }
        ctx.getSource().sendSuccess(() ->
                Component.literal("细粒度并行已" + (enabled ? "开启" : "关闭")), false);
        return 1;
    }

    private static int setNoCollide(CommandContext<CommandSourceStack> ctx, boolean enabled) {
        ModZuozhi.MOB_COLLISION_OFF = enabled;
        ctx.getSource().sendSuccess(() ->
                Component.literal("实体间碰撞已" + (enabled ? "禁用" : "恢复")), false);
        ModZuozhi.LOGGER.info("[ModZuozhi] 实体间碰撞已切换为 {}", enabled ? "禁用" : "恢复");
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        String fine = FineGrainScheduler.isEnabled() ? "开启" : "关闭";
        String noCollide = ModZuozhi.MOB_COLLISION_OFF ? "开启" : "关闭";
        String fineMark = FaultGuard.isFineDegraded() ? "（已自动降级）" : "";
        // 全局始终默认启用，不提供开关；FaultGuard 若触发全局降级，其信息已写入 reason。
        String reason = FaultGuard.getReason();

        ServerLevel level = ctx.getSource().getLevel();
        ResourceKey<Level> dimension = level.dimension();
        // 维度并行激活时：维度 loop 的真实 MSPT（recordDimension 持续更新）；
        // 全局被 FaultGuard 降级关闭（退回原版串行，维度 loop 已停、recordDimension 不再更新）时：
        // 用主线程 tick 间隔样本，它才是当前真实 tick 周期。
        double tps = DimThreadCore.MANAGER.isActive(level.getServer())
                ? TpsTracker.tps(dimension)
                : TpsTracker.tps();

        String msg = String.format(
                "modzuozhi 状态：细粒度=%s%s，禁碰撞=%s，故障原因：%s\n当前维度：%s，TPS：%.1f",
                fine, fineMark, noCollide, reason,
                dimensionName(dimension), tps);
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    /** 把维度 key 映射成玩家可读的中文名。 */
    private static String dimensionName(ResourceKey<Level> dimension) {
        return switch (dimension.location().getPath()) {
            case "overworld" -> "主世界";
            case "the_nether" -> "下界";
            case "the_end" -> "末地";
            default -> dimension.location().toString();
        };
    }
}