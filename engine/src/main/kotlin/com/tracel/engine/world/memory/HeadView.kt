package com.tracel.engine.world.memory

import com.tracel.model.world.WorldChange
import kotlinx.atomicfu.atomic
import java.util.concurrent.ConcurrentSkipListMap

/**
 * The first [limit] entries of [newestFirst] as a list. Walking it reads the map as it is; asking for an index, a
 * sub-list, or a list iterator copies those entries once.
 */
internal class HeadView(
    private val newestFirst: ConcurrentSkipListMap<Long, WorldChange>,
    private val limit: Int,
) : List<WorldChange> {
    private val copy = atomic<ArrayList<WorldChange>?>(null)

    override val size: Int
        get() {
            copy.value?.let { return it.size }
            val n = newestFirst.size
            return if (limit < n) limit else n
        }

    override fun isEmpty(): Boolean = size == 0

    override fun contains(element: WorldChange): Boolean {
        for (change in this) if (change == element) return true
        return false
    }

    override fun containsAll(elements: Collection<WorldChange>): Boolean {
        for (element in elements) if (!contains(element)) return false
        return true
    }

    override fun get(index: Int): WorldChange = snapshot()[index]

    override fun indexOf(element: WorldChange): Int = snapshot().indexOf(element)

    override fun lastIndexOf(element: WorldChange): Int = snapshot().lastIndexOf(element)

    override fun iterator(): Iterator<WorldChange> {
        copy.value?.let { return it.iterator() }
        val inner = newestFirst.values.iterator()
        val n = size
        return object : Iterator<WorldChange> {
            private var seen = 0
            override fun hasNext(): Boolean = seen < n && inner.hasNext()
            override fun next(): WorldChange {
                if (!hasNext()) throw NoSuchElementException()
                seen++
                return inner.next()
            }
        }
    }

    override fun listIterator(): ListIterator<WorldChange> = snapshot().listIterator()

    override fun listIterator(index: Int): ListIterator<WorldChange> = snapshot().listIterator(index)

    override fun subList(fromIndex: Int, toIndex: Int): List<WorldChange> =
        snapshot().subList(fromIndex, toIndex)

    private fun snapshot(): ArrayList<WorldChange> {
        copy.value?.let { return it }
        val n = size
        val out = ArrayList<WorldChange>(n)
        for ((i, change) in newestFirst.values.withIndex()) {
            if (i == n) break
            out.add(change)
        }
        return if (copy.compareAndSet(null, out)) out else copy.value ?: out
    }
}
