package dev.modzuozhi;

import com.mojang.logging.LogUtils;
import dev.modzuozhi.command.MasterCommand;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;

@Mod(ModZuozhi.MOD_ID)
public class ModZuozhi {
    public static final String MOD_ID = "dimensionalripper";
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 全局主开关：false 时整个模组退化为原版（所有并行环节关闭），运行时可由
     * {@code /dimensionalripper on|off} 切换，无需修改配置或重启。
     */
    public static volatile boolean ENABLED = true;

    /** 全局主开关是否开启。所有 mixin 的并行入口都应先判断此开关。 */
    public static boolean isEnabled() {
        return ENABLED;
    }

    /**
     * 是否禁用实体间碰撞（Mob Collision Off 模式，释放大量 TPS 红利）。
     * 默认开启（已长时间测试稳定），可由 {@code /dimensionalripper nocollide on|off} 运行时切换。
     */
    public static volatile boolean MOB_COLLISION_OFF = true;

    public ModZuozhi(IEventBus modEventBus) {
        LOGGER.info("[ModZuozhi] 初始化多线程模组（dimthreads 式维度并行）...");
    }

    @EventBusSubscriber(modid = MOD_ID)
    public static class CommandRegistration {
        @SubscribeEvent
        public static void onRegisterCommands(RegisterCommandsEvent event) {
            MasterCommand.register(event.getDispatcher());
        }
    }
}