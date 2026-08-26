package dev.modzuozhi.core.collection;

import it.unimi.dsi.fastutil.longs.AbstractLongSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectFunction;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongFunction;

/**
 * 第11步：SECTION_LOCK → 并发集合——并发的 {@link Long2ObjectMap} 实现（借鉴 MCMT 思路，独立重写）。
 * <p>
 * {@code EntitySectionStorage.sections} 字段类型是 fastutil {@code Long2ObjectMap}，mixin 无法改字段类型，
 * 只能替换<b>实例</b>。fastutil 没有现成的并发 {@code Long2ObjectMap}，因此继承
 * {@link Long2ObjectOpenHashMap}（保持字段类型兼容），内部改用 {@link ConcurrentHashMap} 作为实际存储，
 * 仅覆盖被 {@code EntitySectionStorage} 实际调用的方法（get/remove/computeIfAbsent/keySet/size/...）。
 * <p>
 * 弱一致性说明：{@link #keySet()} 返回的 {@code LongSet} 是 {@link ConcurrentHashMap#keySet()} 的
 * 弱一致视图，并发增删期间迭代不会抛 {@code ConcurrentModificationException}，可安全用于
 * {@code getAllChunksWithExistingSections} 的结构遍历。
 * <p>
 * 未覆盖的方法（entrySet/long2ObjectEntrySet/values/putAll 等）会落到父类的内部 fastutil 数组（恒空），
 * 因 {@code EntitySectionStorage} 从不对 {@code sections} 调用它们，故无影响（若未来使用需补充覆盖）。
 */
public final class ConcurrentLong2ObjectMap<V> extends Long2ObjectOpenHashMap<V> {
    private static final long serialVersionUID = 1L;

    /** 实际存储：并发哈希表。 */
    private final Map<Long, V> backing = new ConcurrentHashMap<>();

    @Override
    public V get(long key) {
        V v = backing.get(key);
        return (v == null && !backing.containsKey(key)) ? defaultReturnValue() : v;
    }

    @Override
    public V get(Object key) {
        return (key instanceof Long) ? get((long) (Long) key) : defaultReturnValue();
    }

    @Override
    public boolean containsKey(long key) {
        return backing.containsKey(key);
    }

    @Override
    public boolean containsKey(Object key) {
        return key instanceof Long && backing.containsKey(key);
    }

    @Override
    public V put(long key, V value) {
        return backing.put(key, value);
    }

    @Override
    public V put(Long key, V value) {
        return backing.put(key, value);
    }

    @Override
    public V remove(long key) {
        return backing.remove(key);
    }

    @Override
    public V remove(Object key) {
        return backing.remove(key);
    }

    @Override
    public boolean remove(long key, Object value) {
        return backing.remove(key, value);
    }

    @Override
    public boolean remove(Object key, Object value) {
        return key instanceof Long && backing.remove(key, value);
    }

    @Override
    public V computeIfAbsent(long key, Long2ObjectFunction<? extends V> mappingFunction) {
        V v = backing.computeIfAbsent(key, l -> mappingFunction.get(l));
        return v == null ? defaultReturnValue() : v;
    }

    @Override
    public V computeIfAbsent(long key, LongFunction<? extends V> mappingFunction) {
        V v = backing.computeIfAbsent(key, l -> mappingFunction.apply(l));
        return v == null ? defaultReturnValue() : v;
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
    public LongSet keySet() {
        return new BackedKeySet(backing.keySet());
    }

    /** {@link ConcurrentHashMap#keySet()} 的 fastutil 弱一致 {@code LongSet} 视图。 */
    private static final class BackedKeySet extends AbstractLongSet {
        private static final long serialVersionUID = 1L;

        private final Set<Long> backing;

        BackedKeySet(Set<Long> backing) {
            this.backing = backing;
        }

        @Override
        public LongIterator iterator() {
            return new IteratorView(backing.iterator());
        }

        @Override
        public int size() {
            return backing.size();
        }

        @Override
        public boolean add(long k) {
            return backing.add(k);
        }

        @Override
        public boolean remove(long k) {
            return backing.remove(k);
        }

        @Override
        public boolean contains(long k) {
            return backing.contains(k);
        }

        @Override
        public boolean contains(Object o) {
            return backing.contains(o);
        }

        @Override
        public boolean remove(Object o) {
            return backing.remove(o);
        }

        @Override
        public void clear() {
            backing.clear();
        }
    }

    /** 把 {@code java.util.Iterator<Long>} 包装成 fastutil 原语迭代器。 */
    private static final class IteratorView implements LongIterator {
        private final java.util.Iterator<Long> delegate;

        IteratorView(java.util.Iterator<Long> delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean hasNext() {
            return delegate.hasNext();
        }

        @Override
        public long nextLong() {
            return delegate.next();
        }

        @Override
        public Long next() {
            return delegate.next();
        }
    }
}
