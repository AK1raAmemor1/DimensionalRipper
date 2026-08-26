package dev.modzuozhi.core.dimthread;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 区块环境 tick（随机 tick / 流体）的并行版本入口，由 {@code ServerLevelChunkTickMixin} 注入。
 * <p>
 * 并行子任务需要在线程本地随机源上执行随机 tick，但原版 {@code ServerLevel.tickChunk} 直接访问
 * {@code Level.random}（{@code public final} 共享字段）。由于在 NeoForge 开发环境无法对
 * Minecraft 混淆字段做 {@code @Redirect}（无 refmap），这里改为在 {@code ServerLevel} 上提供
 * 一个<b>等价的自定义实现</b> {@link #modzuozhi_tickChunk}，它接收线程本地随机源作为参数，
 * 由 {@code ServerChunkCacheMixin} 在并行子任务中调用，从而完全绕开共享随机源。
 */
public interface IChunkEnvTick {
    void modzuozhi_tickChunk(LevelChunk chunk, int randomTickSpeed, RandomSource random);
}
