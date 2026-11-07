package com.tracel.storage.ports.log

import com.tracel.storage.StorageUnit
import com.tracel.storage.codec.Tag
import com.tracel.storage.spi.EngineCursor
import com.tracel.storage.util.Accepted
import com.tracel.storage.util.concatenated
import com.tracel.storage.util.eachIndex
import com.tracel.storage.util.eachTask
import java.lang.foreign.MemorySegment

private const val MIN_FETCH_SLICE = 4096
private const val MIN_SCAN_BATCH = 64
private const val SKIP_GAP = 64L
private const val SCAN_DENSITY = 64

internal const val SLICES_PER_THREAD = 4

/**
 * The records for [sorted], fetched by as many cursors as the pool has threads, each over its own
 * contiguous slice, and kept where [keep] says so, ascending.
 */
internal fun StorageUnit.fetchSlices(
    tag: Tag,
    sorted: LongArray,
    keep: (MemorySegment) -> Boolean,
): Accepted {
    val n = sorted.size
    if (!worthScanning(sorted)) {
        QueryProbe.pointGot(n.toLong())
        val hits = arrayOfNulls<MemorySegment>(n)
        eachIndex(n) { i -> get(tag[sorted[i]])?.let { if (keep(it)) hits[i] = it } }
        val out = Accepted(n)
        for (i in 0 until n) {
            val hit = hits[i] ?: continue
            out.seqs.add(sorted[i])
            out.records += hit
        }
        return out
    }
    val slices = minOf(sliceCount(n) * SLICES_PER_THREAD, maxOf(1, n / MIN_FETCH_SLICE))
    if (slices <= 1) return fetchSlice(tag, sorted, keep)
    val parts = arrayOfNulls<Accepted>(slices)
    eachTask(slices) { s ->
        val from = (s.toLong() * n / slices).toInt()
        val to = ((s + 1).toLong() * n / slices).toInt()
        parts[s] = fetchSlice(tag, sorted.copyOfRange(from, to), keep)
    }
    return concatenated(parts)
}

/** [fetchSlices] for one slice on this thread. */
internal fun StorageUnit.fetchSlice(
    tag: Tag,
    sorted: LongArray,
    keep: (MemorySegment) -> Boolean,
): Accepted {
    val out = Accepted(sorted.size)
    if (worthScanning(sorted)) {
        QueryProbe.scanned(sorted.size.toLong())
        walkWanted(tag, sorted, 0, sorted.size) { seq, cursor ->
            val record = cursor.value()
            if (keep(record)) {
                out.seqs.add(seq)
                out.records += record
            }
        }
    } else {
        QueryProbe.pointGot(sorted.size.toLong())
        for (seq in sorted) {
            val record = get(tag[seq]) ?: continue
            if (keep(record)) {
                out.seqs.add(seq)
                out.records += record
            }
        }
    }
    return out
}

/** Walks the [tag] family for the keys in `sorted[from until to]`, one cursor for all of them. */
internal inline fun StorageUnit.walkWanted(
    tag: Tag,
    sorted: LongArray,
    from: Int,
    to: Int,
    row: (Long, EngineCursor) -> Unit,
) {
    if (from >= to) return
    var i = from
    val last = sorted[to - 1]
    scan(tag.prefix, tag[sorted[from]], if (last == Long.MAX_VALUE) null else tag[last + 1]).use { cursor ->
        while (i < to && cursor.next()) {
            val at = cursor.keyU64(1)
            while (i < to && sorted[i] < at) i++
            if (i == to) return
            if (sorted[i] == at) {
                row(at, cursor)
                continue
            }
            if (sorted[i] - at > SKIP_GAP) cursor.skipTo(tag[sorted[i]])
        }
    }
}

/** How many cursors to split a batch of [n] keys across. */
internal fun sliceCount(n: Int): Int =
    minOf(maxOf(1, n / MIN_SCAN_BATCH), Runtime.getRuntime().availableProcessors())

/**
 * Whether [sorted] is dense enough in the range it spans to be worth one walk instead of a point
 * get each.
 *
 * A cursor step is far cheaper than a descent, so this does not need to be near one; an eighth is
 * already comfortably ahead, and below that the point gets win.
 */
internal fun worthScanning(sorted: LongArray): Boolean {
    if (sorted.size < MIN_SCAN_BATCH) return false
    val span = sorted.last() - sorted.first() + 1
    return span > 0 && sorted.size.toLong() * SCAN_DENSITY >= span
}
