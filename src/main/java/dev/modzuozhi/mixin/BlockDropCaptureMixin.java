package dev.modzuozhi.mixin;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Block;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.List;

/**
 * 让 NeoForge 的方块掉落捕获对多线程安全。
 * <p>
 * NeoForge 在 {@code Block.dropResources} 中通过<b>全局静态字段</b> {@code Block.capturedDrops}
 * 临时捕获本方块掉落的 {@code ItemEntity}（详见 {@code beginCapturingDrops}/{@code stopCapturingDrops}，
 * 注释明确要求「只在服务端线程调用」）。维度并行 / 细粒度并行下，多个线程并发执行随机 tick
 * （树叶凋零、方块燃烧等）都会进入 {@code dropResources}，并发读写该静态字段会互相覆盖：
 * 某个线程的 {@code stopCapturingDrops()} 会读到已被另一线程清空的 {@code null}，导致
 * {@code BlockDropsEvent.getDrops()} 为 null 而 NPE（日志中 {@code CommonHooks.handleBlockDrops}
 * 的崩溃即源于此），并连带产生 {@code ClassCastException}。
 * <p>
 * 这里把掉落捕获改为<b>线程本地</b>：每个线程维护独立的捕获列表，从根上消除跨线程污染。
 * 语义与原版一致（仅在捕获开始到结束之间拦截掉落），但并行安全。
 */
@Mixin(Block.class)
public abstract class BlockDropCaptureMixin {
    /** 线程本地掉落捕获列表，替代原版全局静态 {@code Block.capturedDrops}。 */
    @Unique
    private static final ThreadLocal<List<ItemEntity>> modzuozhi_capturedDrops = new ThreadLocal<>();

    @Redirect(method = "dropResources",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/Block;beginCapturingDrops()V"))
    private static void modzuozhi_beginCapture() {
        modzuozhi_capturedDrops.set(new ArrayList<>());
    }

    @Redirect(method = "dropResources",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/Block;stopCapturingDrops()Ljava/util/List;"))
    private static List<ItemEntity> modzuozhi_stopCapture() {
        List<ItemEntity> drops = modzuozhi_capturedDrops.get();
        modzuozhi_capturedDrops.remove();
        return drops;
    }

    @Redirect(method = "popResource(Lnet/minecraft/world/level/Level;Ljava/util/function/Supplier;Lnet/minecraft/world/item/ItemStack;)V",
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/world/level/block/Block;capturedDrops:Ljava/util/List;",
                    opcode = Opcodes.GETSTATIC))
    private static List<ItemEntity> modzuozhi_capturedGet() {
        return modzuozhi_capturedDrops.get();
    }
}
