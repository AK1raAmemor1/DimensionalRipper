package dev.modzuozhi.mixin;

import dev.modzuozhi.core.dimthread.IChunkEnvTick;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.animal.horse.SkeletonHorse;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.LightningRodBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * 区块环境随机 tick / 流体 tick 的并行版本（自定义实现，绕开共享 {@code Level.random}）。
 * <p>
 * 细粒度并行（fine）下多个区块的随机 tick 由不同子任务线程执行。原版
 * {@code ServerLevel.tickChunk} 直接读取 {@code Level.random}（{@code public final} 共享字段），
 * 并行时会跨线程冲突；且 NeoForge 开发环境无法对混淆字段做 {@code @Redirect}（无 refmap）。
 * <p>
 * 因此本 mixin 在 {@code ServerLevel} 上注入一个<b>等价的自定义实现</b>
 * {@link #modzuozhi_tickChunk}（实现 {@link IChunkEnvTick}），接收线程本地随机源为参数，
 * 复刻原版 {@code tickChunk} 的 thunder / ice-and-snow / 随机 tick + 流体逻辑。随机源全部来自
 * 参数 {@code random}，由 {@code ServerChunkCacheMixin} 在并行子任务中调用，完全绕开共享字段。
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelChunkTickMixin implements IChunkEnvTick {
    /** {@code findLightningTargetAround} 为 protected，需 shadow 后在本实现内调用。 */
    @Shadow
    protected abstract BlockPos findLightningTargetAround(BlockPos pos);

    /**
     * 等价于原版 {@code ServerLevel.tickChunk}，但使用传入的线程本地随机源 {@code random}。
     */
    @Override
    @Unique
    public void modzuozhi_tickChunk(LevelChunk chunk, int randomTickSpeed, RandomSource random) {
        ServerLevel level = (ServerLevel) (Object) this;
        ChunkPos chunkpos = chunk.getPos();
        boolean raining = level.isRaining();
        int minX = chunkpos.getMinBlockX();
        int minZ = chunkpos.getMinBlockZ();

        // thunder：用线程本地随机源判断是否产生闪电
        if (raining && level.isThundering() && random.nextInt(100000) == 0) {
            BlockPos target = this.findLightningTargetAround(randomPos(minX, 0, minZ, 15, random));
            if (level.isRainingAt(target)) {
                DifficultyInstance difficulty = level.getCurrentDifficultyAt(target);
                boolean trapHorse = level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING)
                        && random.nextDouble() < (double) difficulty.getEffectiveDifficulty() * 0.01
                        && !(level.getBlockState(target.below()).getBlock() instanceof LightningRodBlock);
                if (trapHorse) {
                    SkeletonHorse horse = EntityType.SKELETON_HORSE.create(level);
                    if (horse != null) {
                        horse.setTrap(true);
                        horse.setAge(0);
                        horse.setPos((double) target.getX(), (double) target.getY(), (double) target.getZ());
                        level.addFreshEntity(horse);
                    }
                }
                LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
                if (bolt != null) {
                    bolt.moveTo(Vec3.atBottomCenterOf(target));
                    bolt.setVisualOnly(trapHorse);
                    level.addFreshEntity(bolt);
                }
            }
        }

        // ice and snow（降水 / 结冰）
        for (int i = 0; i < randomTickSpeed; i++) {
            if (random.nextInt(48) == 0) {
                level.tickPrecipitation(randomPos(minX, 0, minZ, 15, random));
            }
        }

        // 随机 tick + 流体 tick
        if (randomTickSpeed > 0) {
            LevelChunkSection[] sections = chunk.getSections();
            for (int idx = 0; idx < sections.length; idx++) {
                LevelChunkSection section = sections[idx];
                if (section.isRandomlyTicking()) {
                    int sectionY = chunk.getSectionYFromSectionIndex(idx);
                    int baseY = SectionPos.sectionToBlockCoord(sectionY);
                    for (int l = 0; l < randomTickSpeed; l++) {
                        BlockPos pos = randomPos(minX, baseY, minZ, 15, random);
                        BlockState state = section.getBlockState(pos.getX() - minX, pos.getY() - baseY, pos.getZ() - minZ);
                        if (state.isRandomlyTicking()) {
                            state.randomTick(level, pos, random);
                        }
                        FluidState fluid = state.getFluidState();
                        if (fluid.isRandomlyTicking()) {
                            fluid.randomTick(level, pos, random);
                        }
                    }
                }
            }
        }
    }

    /** 等价于原版 {@code ServerLevel.getBlockRandomPos}，但使用传入的随机源。 */
    @Unique
    private static BlockPos randomPos(int x, int y, int z, int mask, RandomSource random) {
        return new BlockPos(x + random.nextInt(mask + 1), y, z + random.nextInt(mask + 1));
    }
}
