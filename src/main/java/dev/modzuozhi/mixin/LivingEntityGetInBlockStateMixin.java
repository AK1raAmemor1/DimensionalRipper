package dev.modzuozhi.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 防御：{@code Entity.getInBlockState()} 空值兜底。
 * <p>
 * 罕见 NPE 根因：并行子任务线程上，{@code getInBlockState()} 读取实体所在位置方块状态时
 * 偶发返回 {@code null}（区块读路径的边缘竞态），随后该值被传给
 * {@code CommonHooks.isLivingOnLadder(state, ...)} 并直接 {@code state.isLadder(...)} → NPE
 * （日志：[FineGrain] 子任务异常 ... because "state" is null，50 分钟约 1 次，被屏障捕获不降级）。
 * <p>
 * 注意：{@code getInBlockState()} 定义在 {@link Entity}（父类）而非 {@code LivingEntity}，
 * 因此 {@code @WrapMethod} 必须目标 {@code Entity.class}（mixin 只包装目标类<b>声明</b>的方法）。
 * 这里对返回值做空值兜底（null → 空气方块），从根源上杜绝该路径的 NPE；正常情况零改动。
 */
@Mixin(Entity.class)
public abstract class LivingEntityGetInBlockStateMixin {
    @WrapMethod(method = "getInBlockState")
    private BlockState modzuozhi_nullSafeInBlockState(Operation<BlockState> original) {
        BlockState state = original.call();
        return state == null ? Blocks.AIR.defaultBlockState() : state;
    }
}
