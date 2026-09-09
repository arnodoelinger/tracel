package com.tracel.engine.world

import com.tracel.annotations.Reads
import com.tracel.annotations.RunsOn
import com.tracel.annotations.SingleWriter
import com.tracel.annotations.ThreadContext
import com.tracel.annotations.isBookkeeping
import com.tracel.engine.log.LookupFilter
import com.tracel.engine.ownership.SingleWriterGuard
import com.tracel.model.world.BlockPos
import com.tracel.model.world.ChangeSubject
import com.tracel.model.world.WorldChange
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap
import kotlinx.atomicfu.atomic

/** In-memory [WorldLog]. The reference semantics the native one is checked against. */
@SingleWriter
@RunsOn(ThreadContext.STORAGE)
public class InMemoryWorldLog : WorldLog {
    private val writer = SingleWriterGuard()

    private val bySeq = ConcurrentSkipListMap<Long, WorldChange>()
    private val byPos = ConcurrentHashMap<BlockPos, ConcurrentSkipListMap<Long, WorldChange>>()

    override suspend fun append(change: WorldChange) {
        writer.checkIn()
        val key = descKey(change.seq.raw)
        check(bySeq.putIfAbsent(key, change) == null) {
            "world change at ${change.seq} already appended — the log is append-only"
        }
        var column = byPos[change.at]
        if (column == null) {
            column = ConcurrentSkipListMap()
            byPos[change.at] = column
        }
        column[key] = change
    }

    @Reads
    override suspend fun at(pos: BlockPos, limit: Int): List<WorldChange> {
        if (limit <= 0) return emptyList()
        val column = byPos[pos] ?: return emptyList()
        if (column.isEmpty()) return emptyList()
        return HeadView(column, limit) // Do not use [i]
    }

    @Reads
    override suspend fun query(filter: LookupFilter): List<WorldChange> {
        if (filter.limit <= 0) return emptyList()
        var skipped = 0
        var out: ArrayList<WorldChange>? = null
        for (change in bySeq.values) {
            if (!change.matches(filter)) continue
            if (skipped < filter.offset) {
                skipped++
                continue
            }
            val dest = out
            if (dest == null) {
                val created = ArrayList<WorldChange>(pageCapacity(filter.limit))
                created.add(change)
                out = created
                if (filter.limit == 1) return created
            } else {
                dest.add(change)
                if (dest.size == filter.limit) return dest
            }
        }
        return out ?: emptyList()
    }

    private fun WorldChange.matches(filter: LookupFilter): Boolean {
        if (cause.isBookkeeping) return false
        if (filter.since != null && epochMillis < filter.since) return false
        if (filter.until != null && epochMillis > filter.until) return false
        if (filter.causes.isNotEmpty() && cause !in filter.causes) return false
        if (filter.excludedCauses.isNotEmpty() && cause in filter.excludedCauses) return false
        if (filter.actions.isNotEmpty() && action !in filter.actions) return false

        val by = causedBy
        if (filter.holders.isNotEmpty() && (by == null || by !in filter.holders)) return false
        if (by != null && by in filter.excludedHolders) return false

        if (filter.world != null && at.world != filter.world) return false
        if (filter.region != null && !filter.region.contains(at)) return false

        val material = filter.material
        if (material != null) {
            val subject = subject
            if (subject !is ChangeSubject.Block) return false
            if (!subject.matchesMaterial(material)) return false
        }

        return true
    }

    private fun ChangeSubject.Block.matchesMaterial(material: String): Boolean =
        before.data.value.materialEquals(material) || after.data.value.materialEquals(material)

    @Reads
    public fun all(): Collection<WorldChange> = bySeq.values

    private companion object {
        fun descKey(seq: Long): Long = Long.MAX_VALUE - seq

        fun pageCapacity(limit: Int): Int = if (limit in 1..1024) limit else 8

        fun String.materialEquals(material: String): Boolean {
            val end = indexOf('[')
            val storedEnd = if (end < 0) length else end
            if (storedEnd == material.length && regionMatches(0, material, 0, storedEnd, ignoreCase = true)) return true
            val storedColon = indexOfColon(this, storedEnd)
            val storedFrom = if (storedColon < 0) 0 else storedColon + 1
            val wantedColon = material.indexOf(':')
            val wantedFrom = if (wantedColon < 0) 0 else wantedColon + 1
            val storedLen = storedEnd - storedFrom
            val wantedLen = material.length - wantedFrom
            return storedLen == wantedLen && regionMatches(storedFrom, material, wantedFrom, storedLen, ignoreCase = true)
        }

        fun indexOfColon(value: String, end: Int): Int {
            val at = value.indexOf(':')
            return if (at in 0 until end) at else -1
        }
    }
}

private class HeadView(
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
