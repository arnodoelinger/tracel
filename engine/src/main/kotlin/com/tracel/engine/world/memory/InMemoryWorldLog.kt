package com.tracel.engine.world.memory

import com.tracel.annotations.Reads
import com.tracel.annotations.RunsOn
import com.tracel.annotations.SingleWriter
import com.tracel.annotations.ThreadContext
import com.tracel.engine.log.lookup.LookupFilter
import com.tracel.engine.log.lookup.NameMatcher
import com.tracel.platform.concurrency.SingleWriterGuard
import com.tracel.model.world.BlockPos
import com.tracel.model.world.ChangeSubject
import com.tracel.model.world.WorldChange
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap
import com.tracel.engine.world.WorldLog

/**
 * In-memory [WorldLog], the reference the stored one is checked against.
 *
 * Changes are kept newest first, both overall and per cell, so that asking what happened at a cell is a read from the
 * front. [names] decides when a block name matches the one a filter asks for.
 */
@SingleWriter
@RunsOn(ThreadContext.STORAGE)
public class InMemoryWorldLog(private val names: NameMatcher = NameMatcher.Exact) : WorldLog {
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

    /** Every change, newest first. For tests. */
    @Reads
    public fun all(): Collection<WorldChange> = bySeq.values

    private fun WorldChange.matches(filter: LookupFilter): Boolean {
        if (cause.isBookkeeping) return false
        if (!filter.admitsTime(epochMillis)) return false
        if (!filter.admitsCause(cause, filter.worldCauses ?: filter.causes)) return false
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
            if (!subject.matchesMaterial(material) && filter.blockMaterials.none { subject.matchesMaterial(it) }) return false
        }

        return true
    }

    private fun ChangeSubject.Block.matchesMaterial(material: String): Boolean =
        names.matches(before.data.value, material) || names.matches(after.data.value, material)

    private companion object {
        fun descKey(seq: Long): Long = Long.MAX_VALUE - seq
        fun pageCapacity(limit: Int): Int = if (limit in 1..1024) limit else 8
    }
}
