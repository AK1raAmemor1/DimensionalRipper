package dev.modzuozhi.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * 爆炸全链路串行化（借鉴 Async 思路，独立实现）。
 * <p>
 * 爆炸会<b>批量写入区块方块状态</b>（{@code Explosion.explode} → {@code Level.setBlock} 写
 * 非线程安全的 {@code PalettedContainer}）。并行环境下，若两个爆炸并发执行（或多个爆炸实体/
 * TNT 方块/指令/下界床等不同触发源同时引爆），会并发写同一/相邻区块 → 方块状态撕裂损坏
 * （放置方块消失、被破坏方块"复活"）。爆炸实体已由 {@code EntityTickFilter} 黑名单强制串行，
 * 但爆炸还有 TNT 方块、下界床/重生锚、指令等其它触发源，需在<b>方法入口</b>兜底。
 * <p>
 * 原理：{@code Level} 上所有 {@code explode(...)} 重载与 {@code ServerLevel} 的覆写最终都
 * <b>委托到 13 参重载</b>（唯一真正创建 {@code Explosion} 并执行 {@code explode()}+
 * {@code finalizeExplosion()} 的方法）。因此只需给该终端重载加一把<b>静态全局锁</b>，所有
 * 触发源的爆炸便全部互斥串行。爆炸低频、锁竞争可忽略；该方法不递归调用
 * {@code Level.explode}，静态单锁无锁序环，无死锁风险。
 */
@Mixin(Level.class)
public abstract class LevelExplodeMixin {

    /** 全局爆炸互斥锁（跨维度/跨触发源统一串行）。 */
    @Unique
    private static final Object MODZUOZHI_EXPLODE_LOCK = new Object();

    @WrapMethod(method = "explode(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/damagesource/DamageSource;Lnet/minecraft/world/level/ExplosionDamageCalculator;DDDFZLnet/minecraft/world/level/Level$ExplosionInteraction;ZLnet/minecraft/core/particles/ParticleOptions;Lnet/minecraft/core/particles/ParticleOptions;Lnet/minecraft/core/Holder;)Lnet/minecraft/world/level/Explosion;")
    private Explosion modzuozhi_explode(
            Entity source, DamageSource damageSource, ExplosionDamageCalculator damageCalculator,
            double x, double y, double z, float radius, boolean fire, Level.ExplosionInteraction interaction,
            boolean spawnParticles, ParticleOptions smallExplosionParticles, ParticleOptions largeExplosionParticles,
            Holder<SoundEvent> explosionSound, Operation<Explosion> original) {
        synchronized (MODZUOZHI_EXPLODE_LOCK) {
            return original.call(source, damageSource, damageCalculator, x, y, z, radius, fire, interaction,
                    spawnParticles, smallExplosionParticles, largeExplosionParticles, explosionSound);
        }
    }
}
