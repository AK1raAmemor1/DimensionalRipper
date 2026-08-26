package dev.modzuozhi.mixin;

import net.minecraft.util.ClassInstanceMultiMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 第10步：{@code ClassInstanceMultiMap} 内部结构线程安全化（修共享读锁下的 CME）。
 * <p>
 * {@code EntitySection.getEntities(EntityTypeTest,...)} 走 {@code ClassInstanceMultiMap.find}，
 * 其内部执行 {@code byClass.computeIfAbsent(...)} 懒加载类型列表——这是<b>写</b>操作。
 * 第10步把 section 内容查询改成<b>共享读锁</b>后，同一 section 的两个并发查询会同时
 * {@code computeIfAbsent} 同一个普通 {@code HashMap} → {@code ConcurrentModificationException}
 * （日志已证实：村民大脑传感器在 1 分钟内触发 5 次子任务异常导致自动降级）。
 * <p>
 * 修复：把 {@code byClass} 换成 {@link ConcurrentHashMap}——其 {@code computeIfAbsent} 原子且
 * 线程安全（同一 key 的映射函数只执行一次），并发查询互不干扰。既保留共享读锁的并行度，又根除 CME。
 * {@code allInstances} 的读写仍由 section 分片锁（写锁 vs 读锁）互斥，无需改动。
 * <p>
 * 注：字段 final 已由 access transformer（{@code accesstransformer.cfg}）移除，本类在
 * 构造器 RETURN 处替换为并发映射并恢复 {@code baseClass → allInstances} 注册。
 */
@Mixin(ClassInstanceMultiMap.class)
public abstract class ClassInstanceMultiMapMixin<T> {
    @Shadow
    private Map<Class<?>, List<T>> byClass;
    @Shadow
    private Class<T> baseClass;
    @Shadow
    private List<T> allInstances;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void modzuozhi_concurrentByClass(CallbackInfo ci) {
        this.byClass = new ConcurrentHashMap<>();
        this.byClass.put(this.baseClass, this.allInstances);
    }
}
