package dev.modzuozhi.core.dimthread;

/**
 * 供 {@code EntitySectionMixin} 注入的 section key 访问器。
 * <p>
 * {@code EntitySection} 自身不持有其在 {@code EntitySectionStorage} 中的 section key，
 * 细粒度分片锁需要该 key 定位锁片。本接口由 {@code EntitySectionMixin} 实现，注入
 * {@code @Unique} 字段保存 key，并由 {@code EntitySectionStorageMixin} 在
 * {@code createSection} 时写入。
 * <p>
 * 注意：该接口<b>不能</b>放在 mixin 包内——Mixin 禁止从被转换类直接引用 mixin 包中的
 * 类型（{@code IllegalClassLoadError}）。因此放在核心包 {@code core.dimthread}。
 */
public interface ISectionKeyAccessor {
    void modzuozhi_setSectionKey(long key);

    long modzuozhi_getSectionKey();
}
