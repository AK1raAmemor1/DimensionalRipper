package dev.modzuozhi.core.collection;

import it.unimi.dsi.fastutil.longs.AbstractLongBidirectionalIterator;
import it.unimi.dsi.fastutil.longs.AbstractLongSortedSet;
import it.unimi.dsi.fastutil.longs.LongBidirectionalIterator;
import it.unimi.dsi.fastutil.longs.LongComparator;
import it.unimi.dsi.fastutil.longs.LongSortedSet;

import java.util.NavigableSet;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * 第11步：SECTION_LOCK → 并发集合——并发的 {@link LongSortedSet} 实现（借鉴 MCMT 思路，独立重写）。
 * <p>
 * {@code EntitySectionStorage.sectionIds} 字段类型是 fastutil {@code LongSortedSet}，mixin 无法改字段类型，
 * 只能替换<b>实例</b>。fastutil 没有现成的并发 {@code LongSortedSet}，因此实现
 * {@link LongSortedSet}，内部用 {@link ConcurrentSkipListSet}（有序、弱一致迭代、无 CME）。
 * <p>
 * 与 MCMT 参考实现的关键差异：{@link #subSet}/{@link #headSet}/{@link #tailSet} 返回的是
 * 同一 {@link ConcurrentSkipListSet} 子集视图的弱一致包装（不拷贝快照），避免每查询一次
 * {@code forEachAccessibleNonEmptySection} 就分配一棵新 AVL 树的 GC 开销；迭代器弱一致，
 * 并发增删期间迭代不会抛 {@code ConcurrentModificationException}。
 * <p>
 * {@code EntitySectionStorage.createSection} 先 {@code sectionIds.add} 再建 {@code EntitySection}，
 * 读方在 subSet 迭代中可能遇到尚未写入 {@code sections} 的 key——原版
 * {@code forEachAccessibleNonEmptySection} 已有 {@code sections.get(...) != null} 检查，天然安全。
 */
public final class ConcurrentLongSortedSet extends AbstractLongSortedSet {
    private static final long serialVersionUID = 1L;

    /** 实际存储：有序并发跳表。 */
    private final NavigableSet<Long> back;

    public ConcurrentLongSortedSet() {
        this.back = new ConcurrentSkipListSet<>();
    }

    private ConcurrentLongSortedSet(NavigableSet<Long> back) {
        this.back = back;
    }

    @Override
    public LongBidirectionalIterator iterator() {
        return new SortedIteratorView(back.iterator());
    }

    @Override
    public LongBidirectionalIterator iterator(long fromElement) {
        return new SortedIteratorView(back.tailSet(fromElement, true).iterator());
    }

    @Override
    public int size() {
        return back.size();
    }

    @Override
    public boolean isEmpty() {
        return back.isEmpty();
    }

    @Override
    public boolean add(long k) {
        return back.add(k);
    }

    @Override
    public boolean add(Long k) {
        return back.add(k);
    }

    @Override
    public boolean remove(long k) {
        return back.remove(k);
    }

    @Override
    public boolean remove(Object o) {
        return back.remove(o);
    }

    @Override
    public boolean contains(long k) {
        return back.contains(k);
    }

    @Override
    public boolean contains(Object o) {
        return back.contains(o);
    }

    @Override
    public void clear() {
        back.clear();
    }

    @Override
    public long firstLong() {
        return back.first();
    }

    @Override
    public long lastLong() {
        return back.last();
    }

    @Override
    public LongSortedSet subSet(long fromElement, long toElement) {
        // ConcurrentSkipListSet 的 2 参 subSet 返回 SortedSet；用 4 参重载（[from, to) 语义一致）拿到 NavigableSet 视图
        return new ConcurrentLongSortedSet(back.subSet(fromElement, true, toElement, false));
    }

    @Override
    public LongSortedSet headSet(long toElement) {
        return new ConcurrentLongSortedSet(back.headSet(toElement, false));
    }

    @Override
    public LongSortedSet tailSet(long fromElement) {
        return new ConcurrentLongSortedSet(back.tailSet(fromElement, true));
    }

    @Override
    public LongComparator comparator() {
        return null;
    }

    /** 把 {@code java.util.Iterator<Long>} 包装成 fastutil 双向原语迭代器（previous 不用于本集合场景）。 */
    private static final class SortedIteratorView extends AbstractLongBidirectionalIterator {
        private final java.util.Iterator<Long> delegate;

        SortedIteratorView(java.util.Iterator<Long> delegate) {
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
        public boolean hasPrevious() {
            return false;
        }

        @Override
        public long previousLong() {
            throw new UnsupportedOperationException();
        }
    }
}
