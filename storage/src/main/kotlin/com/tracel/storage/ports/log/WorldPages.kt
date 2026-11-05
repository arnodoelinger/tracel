package com.tracel.storage.ports.log

import com.tracel.engine.log.lookup.LookupFilter
import com.tracel.model.cause.CauseKind
import com.tracel.model.holder.HolderId
import com.tracel.model.log.Seq
import com.tracel.model.world.*
import com.tracel.model.world.block.BlockShape
import com.tracel.storage.StorageUnit
import com.tracel.storage.codec.Tag
import com.tracel.storage.codec.EntityChange
import com.tracel.storage.codec.Wchg
import com.tracel.storage.intern.Interning
import com.tracel.storage.util.Accepted
import com.tracel.storage.util.eachBatch
import com.tracel.storage.util.eachIndex
import it.unimi.dsi.fastutil.longs.LongArrayList
import java.lang.foreign.MemorySegment
import java.util.Arrays
import java.util.concurrent.atomic.AtomicInteger

/**
 * Whole pages of world changes: the records a query accepted, newest first or cut down to the ends of every
 * structure, and the rows of a spatial walk, most of which never need their record read at all.
 *
 * Interned ids are resolved once per page, then every row is built from that, in parallel.
 */
internal class WorldPages(private val decoder: WorldDecoder, private val interning: Interning) {
    @Suppress("ReplaceManualRangeWithIndicesCalls")
    fun fromRows(
        unit: StorageUnit,
        rows: SpatialRows,
        filter: LookupFilter,
        ids: FilterIds,
        worldId: Int,
        structureEnds: Boolean,
    ): List<WorldChange> {
        val order = QueryProbe.phase("rows order") { rows.ascendingOrder() }
        val n = order.size
        val keep = BooleanArray(n)
        val dataIds = IntArray(n * 2)
        val holderIdsSeen = IntArray(n)
        var dataCount = 0
        var holderCount = 0
        var kept = 0
        var deferredCount = 0
        val acceptAt = System.nanoTime()
        for (i in 0 until n) {
            val row = order[i]
            if (!WorldFilter.acceptsRow(rows, row, filter, ids)) continue
            keep[i] = true
            kept++
            if (rows.deferred[row]) {
                deferredCount++
            } else {
                dataIds[dataCount++] = rows.beforeIds[row]
                dataIds[dataCount++] = rows.afterIds[row]
                if (rows.causedByIds[row] != 0) holderIdsSeen[holderCount++] = rows.causedByIds[row]
            }
        }
        QueryProbe.record("rows accept", System.nanoTime() - acceptAt)
        if (kept == 0) return emptyList()
        QueryProbe.opened(deferredCount.toLong())
        QueryProbe.inlined((kept - deferredCount).toLong())

        val world = interning.resolveWorld(unit, worldId)
        val dataKeys = distinctOf(dataIds, dataCount)
        val shapes = arrayOfNulls<BlockShape>(dataKeys.size)
        for (i in dataKeys.indices) {
            val data = interning.resolveBlockData(unit, dataKeys[i])
            shapes[i] = decoder.shapeOf(data)
        }
        val holderKeys = distinctOf(holderIdsSeen, holderCount)
        val holderValues = arrayOfNulls<HolderId>(holderKeys.size)
        for (i in holderKeys.indices) holderValues[i] = interning.resolveHolder(unit, holderKeys[i])

        val records = arrayOfNulls<MemorySegment>(n)
        if (deferredCount > 0) QueryProbe.phase("rows fetch") {
            val at = IntArray(deferredCount)
            val seqs = LongArray(deferredCount)
            var d = 0
            for (i in 0 until n) {
                if (!keep[i] || !rows.deferred[order[i]]) continue
                at[d] = i
                seqs[d++] = rows.seqs[order[i]]
            }
            val recordIds = ids.copy(regionWorld = worldId, world = null)
            val got = unit.fetchSlices(Tag.WCHG, seqs) { WorldFilter.accepts(unit, interning, it, filter, recordIds) }
            var j = 0
            for (k in 0 until got.seqs.size) {
                val seq = got.seqs.getLong(k)
                while (seqs[j] != seq) j++
                records[at[j++]] = got.records[k]
            }
        }

        // A row that needed its record can turn into any number of changes — a section delta is
        // one row standing for thousands of blocks — so a slot holds a list, not a change.
        val built = arrayOfNulls<WorldChange>(n)
        val expanded = arrayOfNulls<MutableList<WorldChange>>(n)
        val build = { i: Int ->
            if (keep[i]) {
                val row = order[i]
                if (rows.deferred[row]) {
                    records[i]?.let { record ->
                        val out = ArrayList<WorldChange>(1)
                        decoder.expandInto(unit, rows.seqs[row], record, null, filter, out)
                        expanded[i] = out
                    }
                } else {
                    val causedBy = rows.causedByIds[row]
                    built[i] = WorldChange(
                        Seq(rows.seqs[row]),
                        ActionKind.entries[rows.actions[row].toInt() and 0xFF],
                        CauseKind.entries[rows.causes[row].toInt() and 0xFF],
                        if (causedBy == 0) null else holderValues[indexOf(holderKeys, causedBy)],
                        rows.millis[row],
                        BlockPos(world, rows.xs[row], rows.ys[row], rows.zs[row]),
                        ChangeSubject.Block(
                            shapes[indexOf(dataKeys, rows.beforeIds[row])]!!,
                            shapes[indexOf(dataKeys, rows.afterIds[row])]!!,
                        ),
                    )
                }
            }
        }
        QueryProbe.phase("rows build") { eachIndex(n, work = kept, body = build) }

        val ordered = QueryProbe.phase("rows gather") { gathered(n, built, expanded) }
        return if (structureEnds) QueryProbe.phase("rows ends") { structureEndsOf(ordered) } else ordered.asReversed()
    }

    fun decodeAccepted(
        unit: StorageUnit,
        accepted: Accepted,
        filter: LookupFilter,
        structureEnds: Boolean,
    ): List<WorldChange> = QueryProbe.phase("world records decode") {
        if (structureEnds) decodeStructureEnds(unit, accepted.seqs, accepted.records, filter)
        else decodeNewestFirst(unit, accepted.seqs, accepted.records, filter)
    }

    @Suppress("UNCHECKED_CAST")
    private fun gathered(n: Int, built: Array<WorldChange?>, expanded: Array<MutableList<WorldChange>?>): List<WorldChange> {
        val starts = IntArray(n + 1)
        for (i in 0 until n) starts[i + 1] = starts[i] + (if (built[i] != null) 1 else 0) + (expanded[i]?.size ?: 0)
        val all = arrayOfNulls<WorldChange>(starts[n])
        eachBatch(n) { from, to ->
            for (i in from until to) {
                var at = starts[i]
                built[i]?.let { all[at++] = it }
                expanded[i]?.let { for (change in it) all[at++] = change }
            }
        }
        return (all as Array<WorldChange>).asList()
    }

    private fun distinctOf(ids: IntArray, count: Int): IntArray {
        if (count == 0) return IntArray(0)
        val sorted = ids.copyOf(count)
        Arrays.sort(sorted)
        var unique = 1
        for (i in 1 until count) if (sorted[i] != sorted[unique - 1]) sorted[unique++] = sorted[i]
        return if (unique == count) sorted else sorted.copyOf(unique)
    }

    private fun indexOf(keys: IntArray, id: Int): Int = Arrays.binarySearch(keys, id)

    private fun decodeNewestFirst(
        unit: StorageUnit,
        seqs: LongArrayList,
        records: ArrayList<MemorySegment>,
        filter: LookupFilter,
    ): ArrayList<WorldChange> {
        val n = seqs.size
        if (n == 0) return ArrayList()
        val resolved = decoder.resolveAll(unit, records, pick = null)
        val slots = arrayOfNulls<Any>(n)
        val build = { i: Int -> slots[i] = decoder.expandOne(unit, seqs.getLong(i), records[i], resolved, filter) }
        eachIndex(n, body = build)
        val out = ArrayList<WorldChange>(n)
        for (i in n - 1 downTo 0) out.addExpanded(slots[i])
        return out
    }

    private fun decodeStructureEnds(
        unit: StorageUnit,
        seqs: LongArrayList,
        records: ArrayList<MemorySegment>,
        filter: LookupFilter,
    ): ArrayList<WorldChange> {
        val n = seqs.size
        if (n == 0) return ArrayList()

        val ends = EndsIndex(n)
        val pick = BooleanArray(n)
        val sections = AtomicInteger()
        val keysAt = System.nanoTime()
        eachBatch(n) { from, to ->
            var sectionRows = 0
            for (i in from until to) {
                val record = records[i]
                when (Wchg.kind[record]) {
                    Wchg.SECTION -> {
                        pick[i] = true
                        sectionRows++
                    }

                    Wchg.BLOCK -> {
                        ends.k1[i] = (Wchg.x[record].toLong() shl 32) or
                                (Wchg.z[record].toLong() and 0xFFFFFFFFL)
                        ends.k2[i] = (Wchg.y[record].toLong() shl 32) or
                                (Wchg.world[record].toLong() and 0xFFFFFFFFL)
                        ends.kind[i] = BLOCK_KEY
                    }

                    else -> {
                        ends.k1[i] = EntityChange.entity.high(record)
                        ends.k2[i] = EntityChange.entity.low(record)
                        ends.kind[i] = ENTITY_KEY
                    }
                }
            }
            sections.addAndGet(sectionRows)
        }
        val count = sections.get() + ends.mark(pick)
        QueryProbe.record("ends keys", System.nanoTime() - keysAt)

        val resolved = QueryProbe.phase("ends resolve") { decoder.resolveAll(unit, records, pick) }
        val slots = arrayOfNulls<Any>(n)
        val build = { i: Int ->
            if (pick[i]) slots[i] = decoder.expandOne(unit, seqs.getLong(i), records[i], resolved, filter)
        }
        QueryProbe.phase("ends expand") { eachIndex(n, work = count, body = build) }

        val ordered = QueryProbe.phase("ends gather") {
            ArrayList<WorldChange>(count).also { list -> for (i in 0 until n) list.addExpanded(slots[i]) }
        }
        return QueryProbe.phase("ends dedupe") { structureEndsOf(ordered) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun ArrayList<WorldChange>.addExpanded(slot: Any?) {
        when (slot) {
            null -> Unit
            is WorldChange -> add(slot)
            else -> addAll(slot as List<WorldChange>)
        }
    }
}
