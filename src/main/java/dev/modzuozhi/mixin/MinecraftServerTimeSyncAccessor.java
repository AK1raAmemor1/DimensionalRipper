package dev.modzuozhi.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 暴露原版私有的 {@code MinecraftServer.synchronizeTime(ServerLevel)}，供维度 loop 在每次
 * 时间同步（每 20 tick）时用原版逻辑（含 NeoForge 自定义时间载荷）广播时间包。
 */
@Mixin(MinecraftServer.class)
public interface MinecraftServerTimeSyncAccessor {
    @Invoker("synchronizeTime")
    void modzuozhi$invokeSynchronizeTime(ServerLevel level);
}