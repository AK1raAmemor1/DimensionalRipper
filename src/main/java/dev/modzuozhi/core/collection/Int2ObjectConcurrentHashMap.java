package dev.modzuozhi.core.collection;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.AbstractObjectCollection;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ObjectIterators;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 第14步（对齐 Async 零锁路线）：并发的 {@code Int2ObjectMap} 实现（借鉴 MCMT/Async 思路，独立重写）。
 * <p>
 * {@code EntityLookup.byId} 字段类型是 fastutil {@code Int2ObjectMap}，mixin 无法改字段类型，
 * 只能替换<b>实例</b>。fastutil 无现成并发版，因此继承 {@link Int2ObjectOpenHashMap}（保持字段类型兼容），
 * 内部用 {@link ConcurrentHashMap} 作为实际存储，仅覆盖 {@code EntityLookup} 用到的
 * get/put/remove/values/size 等方法。
 * <p>
 * {@link #values()} 返回 {@link ConcurrentHashMap#values()} 的弱一致视图（迭代并发增删不抛 CME），
 * 供 {@code EntityLookup.getEntities/getAllEntities} 安全遍历。
 */
public final class Int2ObjectConcurrentHashMap<V> extends Int2ObjectOpenHashMap<V> {
    private static final long serialVersionUID = 1L;

    /** 实际存储：并发哈希表。 */
    private final Map<Integer, V> backing = new ConcurrentHashMap<>();

    @Override
    public V get(int key) {
        V v = backing.get(key);
        return (v == null && !backing.containsKey(key)) ? defaultReturnValue() : v;
    }

    @Override
    public V get(Object key) {
        return (key instanceof Integer) ? get((int) (Integer) key) : defaultReturnValue();
    }

    @Override
    public boolean containsKey(int key) {
        return backing.containsKey(key);
    }

    @Override
    public boolean containsKey(Object key) {
        return key instanceof Integer && backing.containsKey(key);
    }

    @Override
    public V put(int key, V value) {
        return backing.put(key, value);
    }

    @Override
    public V put(Integer key, V value) {
        return backing.put(key, value);
    }

    @Override
    public V remove(int key) {
        return backing.remove(key);
    }

    @Override
    public V remove(Object key) {
        return backing.remove(key);
    }

    @Override
    public int size() {
        return backing.size();
    }

    @Override
    public boolean isEmpty() {
        return backing.isEmpty();
    }

    @Override
    public void clear() {
        backing.clear();
    }

    @Override
    public ObjectCollection<V> values() {
        return new ValuesView(backing.values());
    }

    /** {@link ConcurrentHashMap#values()} 的弱一致视图（迭代安全）。 */
    private static final class ValuesView<V> extends AbstractObjectCollection<V> {
        private static final long serialVersionUID = 1L;

        private final Collection<V> backing;

        ValuesView(Collection<V> backing) {
            this.backing = backing;
        }

        @Override
        public ObjectIterator<V> iterator() {
            return ObjectIterators.asObjectIterator(backing.iterator());
        }

        @Override
        public int size() {
            return backing.size();
        }
    }
}
