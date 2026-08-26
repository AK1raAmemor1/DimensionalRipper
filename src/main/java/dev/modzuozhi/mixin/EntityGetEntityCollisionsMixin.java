package dev.modzuozhi.mixin;

import dev.modzuozhi.ModZuozhi;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 第11步（可选特性）：禁用实体间碰撞（Mob Collision Off 模式，独立实现）。
 * <p>
 * 两个注入点（对齐 mob-collision-off 模组的 EntityCollideMixin + EntityMixin）：
 * <ul>
 *   <li>{@code @Redirect} {@code Entity.collide(Vec3)} 里的 {@code getEntityCollisions(...)}：
 *       实体<b>移动</b>时不查询/计算附近实体的碰撞形状（消除 O(n²) 热点）。</li>
 *   <li>{@code @Inject} {@code Entity.push(Entity)} HEAD 取消：实体<b>重叠时互相推开</b>的
 *       独立推动机制也一并关闭（否则即使移动碰撞为空，重叠实体仍会被 push 挤开，表现为
 *       "碰撞箱还在"）。攻击击退走 {@code push(double,double,double)} 重载，不受影响。</li>
 * </ul>
 * 方块碰撞不受影响（走另一条路径 {@code BlockCollisions}）。开关：{@code /dimensionalripper nocollide on|off}，
 * 遵守全局开关：{@code /dimensionalripper off} 一并退化为原版。
 * <p>
 * <b>第14步（对齐 Async）</b>：第13步的"碰撞降频"（每 2 tick 评估一次碰撞）已回退——移除全局写锁、
 * 并发化实体存储后，碰撞开启也能并行跑，不再需要降频拐杖。本特性保留为<b>可选开关</b>（默认关闭
 * 碰撞以获取最大 TPS；需要生物交互时开碰撞）。
 */
@Mixin(Entity.class)
public abstract class EntityGetEntityCollisionsMixin {
    @Redirect(method = "collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getEntityCollisions(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"))
    private List<VoxelShape> modzuozhi_noMobCollision(Level level, @Nullable Entity entity, AABB aabb) {
        // 只受 nocollide 开关控制，不随全局开关失效：全局 /dimensionalripper off 后若恢复真实碰撞，
        // 实体密集场景会回归 O(n²) 碰撞热点，主线程被拖到几乎无响应（压测巨量实体时实测卡死）。
        if (ModZuozhi.MOB_COLLISION_OFF) {
            return List.of();
        }
        return level.getEntityCollisions(entity, aabb);
    }

    @Inject(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void modzuozhi_noPush(Entity other, CallbackInfo ci) {
        if (ModZuozhi.MOB_COLLISION_OFF) {
            ci.cancel();
        }
    }
}
