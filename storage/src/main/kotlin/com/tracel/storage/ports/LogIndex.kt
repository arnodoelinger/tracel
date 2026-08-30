package com.tracel.storage.ports

import com.tracel.annotations.CauseKind
import com.tracel.annotations.isBookkeeping
import com.tracel.engine.log.LookupRegion
import com.tracel.model.world.LogKind
import com.tracel.storage.StorageUnit
import com.tracel.storage.codec.KeyReader
import com.tracel.storage.codec.Keys
import com.tracel.storage.codec.Records
import com.tracel.storage.intern.Interning
import com.tracel.storage.spi.EngineCursor

/**
 * One cursor's worth of an index scan: what to walk, where to start, and — for a spatial column
 * — where the wanted part of it ends.
 *
 * [bound] exists because a region is not a prefix. Its chunks share an x before they differ in
 * z, so one column of them is a contiguous run a prefix scan will happily walk straight out of,
 * and each of those chunks holds its whole history under a key the query only wants ten minutes
 * of. An actor or item scan has neither problem and passes none.
 */
internal class Scan(
    val prefix: ByteArray,
    val from: ByteArray = prefix,
    val bound: ColumnBound? = null,
)

/**
 * The edges of one spatial column: the last chunk z that is still inside the region, and the
 * time window each of its chunks is wanted for.
 *
 * Spatial keys run `chunk / ~epochMillis / ~seq`, so a chunk's rows come out newest first and
 * the first one older than [sinceMillis] means the rest of that chunk is older too. That is one
 * seek to the top of the next chunk, not a walk through however many years of water flow sit
 * underneath — and it is why this index no longer buckets by hour.
 */
internal class ColumnBound(
    val worldId: Int,
    val lastChunkZ: Int,
    val sinceMillis: Long?,
    val untilMillis: Long?,
) {
    /** Past the region's last chunk — this column is finished. */
    fun past(key: ByteArray): Boolean = Keys.spatialChunkZ(key) > lastChunkZ

    /** Older than the window: everything below it in this chunk is older still. */
    fun spent(key: ByteArray): Boolean = sinceMillis != null && Keys.spatialMillis(key) < sinceMillis

    /**
     * Newer than the window. Only the column's first chunk is seeked to `until`; every chunk
     * after it is entered at its own newest row, so each one needs the same jump.
     */
    fun tooNew(key: ByteArray): Boolean = untilMillis != null && Keys.spatialMillis(key) > untilMillis

    /** The window's top edge in the chunk [key] is already inside. */
    fun windowTop(key: ByteArray): ByteArray =
        Keys.spatialChunkFrom(worldId, Keys.spatialChunkX(key), Keys.spatialChunkZ(key), untilMillis)

    /** The top of the chunk after the one [key] names, or null when there is no next chunk. */
    fun nextChunk(key: ByteArray): ByteArray? {
        val z = Keys.spatialChunkZ(key)
        if (z >= lastChunkZ) return null
        return Keys.spatialChunkFrom(worldId, Keys.spatialChunkX(key), z + 1, untilMillis)
    }
}

/** Scans over whole keys, for indexes where a prefix is the whole story. */
internal fun scansOver(prefixes: List<ByteArray>): List<Scan> = prefixes.map { Scan(it) }

/** Sequences of [kind] a large query will fetch. */
internal fun StorageUnit.gatherSeqs(
    kind: LogKind,
    scans: List<Scan>?,
    since: Long?,
    until: Long?,
    wanted: Int,
    excludedCauses: Set<CauseKind> = emptySet(),
    region: LookupRegion? = null,
): ArrayList<Long> {
    val out = ArrayList<Long>()
    if (scans != null && scans.isEmpty()) return out
    if (scans != null && wanted >= BATCHED_FROM) {
        if (scans.size == 1) {
            walkColumn(
                scans[0], since, until, excludedCauses, region,
                worldOut = if (kind == LogKind.WORLD) out else null,
                txnOut = if (kind == LogKind.TRANSACTION) out else null,
            )
            return out
        }
        val parts = arrayOfNulls<ArrayList<Long>>(scans.size)
        java.util.stream.IntStream.range(0, scans.size).parallel().forEach { i ->
            val part = ArrayList<Long>()
            walkColumn(
                scans[i], since, until, excludedCauses, region,
                worldOut = if (kind == LogKind.WORLD) part else null,
                txnOut = if (kind == LogKind.TRANSACTION) part else null,
            )
            parts[i] = part
        }
        var n = 0
        for (p in parts) n += p!!.size
        out.ensureCapacity(n)
        for (p in parts) out.addAll(p!!)
        return out
    }

    val drive = { seq: Long -> out += seq; out.size }
    if (scans != null) {
        mergeDescending(
            scans, kind, wanted, drive,
            sinceMillis = since, untilMillis = until, excludedCauses = excludedCauses,
        )
    } else {
        scanDescendingByTime(kind, since, until, wanted, drive)
    }
    return out
}

/** One column of the spatial index, dumping matching sequences. */
internal fun StorageUnit.walkColumn(
    scan: Scan,
    sinceMillis: Long?,
    untilMillis: Long?,
    excludedCauses: Set<CauseKind>,
    region: LookupRegion?,
    worldOut: ArrayList<Long>?,
    txnOut: ArrayList<Long>?,
) {
    var rows = 0L
    var kept = 0L
    scan(scan.prefix, scan.from).use { cursor ->
        val bound = scan.bound
        while (cursor.next()) {
            val key = cursor.key()
            if (bound != null) {
                if (bound.past(key)) break
                if (bound.tooNew(key)) {
                    cursor.skipTo(bound.windowTop(key))
                    continue
                }
                if (bound.spent(key)) {
                    cursor.skipTo(bound.nextChunk(key) ?: break)
                    continue
                }
            }
            rows++
            val value = cursor.value()
            val dest = when (Records.asLogKind(value)) {
                LogKind.WORLD -> worldOut
                LogKind.TRANSACTION -> txnOut
            } ?: continue
            val at = Records.logKindMillis(value)
            if (sinceMillis != null && at != null && at < sinceMillis) continue
            if (untilMillis != null && at != null && at > untilMillis) continue
            val cause = Records.logKindCause(value)
            if (cause != null && cause.isBookkeeping) continue
            if (excludedCauses.isNotEmpty() && cause != null && cause in excludedCauses) continue
            if (region != null) {
                val x = Records.logKindX(value)
                if (x != null && !region.containsBlock(x, Records.logKindY(value)!!, Records.logKindZ(value)!!)) continue
            }
            kept++
            dest += Keys.invert(KeyReader.u64(key, key.size - 8))
        }
    }
    QueryProbe.walked(rows, kept)
}

internal fun StorageUnit.gatherBoth(
    scans: List<Scan>,
    since: Long?,
    until: Long?,
    excludedCauses: Set<CauseKind>,
    region: LookupRegion? = null,
): Pair<ArrayList<Long>, ArrayList<Long>> {
    if (scans.isEmpty()) return ArrayList<Long>() to ArrayList()
    if (scans.size == 1) {
        val world = ArrayList<Long>()
        val txns = ArrayList<Long>()
        walkColumn(scans[0], since, until, excludedCauses, region, world, txns)
        return world to txns
    }
    val worlds = arrayOfNulls<ArrayList<Long>>(scans.size)
    val txns = arrayOfNulls<ArrayList<Long>>(scans.size)
    java.util.stream.IntStream.range(0, scans.size).parallel().forEach { i ->
        val world = ArrayList<Long>()
        val txn = ArrayList<Long>()
        walkColumn(scans[i], since, until, excludedCauses, region, world, txn)
        worlds[i] = world
        txns[i] = txn
    }
    var wN = 0
    var tN = 0
    for (i in scans.indices) {
        wN += worlds[i]!!.size
        tN += txns[i]!!.size
    }
    val world = ArrayList<Long>(wN)
    val txn = ArrayList<Long>(tN)
    for (i in scans.indices) {
        world.addAll(worlds[i]!!)
        txn.addAll(txns[i]!!)
    }
    return world to txn
}

internal fun StorageUnit.gatherBothByTime(
    since: Long?,
    until: Long?,
    excludedCauses: Set<CauseKind>,
    region: LookupRegion? = null,
): Pair<ArrayList<Long>, ArrayList<Long>> {
    val world = ArrayList<Long>()
    val txns = ArrayList<Long>()
    val prefix = byteArrayOf(Keys.TIME)
    val from = if (until != null) Keys.timeFrom(until) else prefix
    scan(prefix, from).use { cursor ->
        while (cursor.next()) {
            val key = cursor.key()
            if (since != null && Keys.invert(KeyReader.u64(key, 1)) < since) break
            val value = cursor.value()
            val dest = when (Records.asLogKind(value)) {
                LogKind.WORLD -> world
                LogKind.TRANSACTION -> txns
            }
            val cause = Records.logKindCause(value)
            if (cause != null && cause.isBookkeeping) continue
            if (excludedCauses.isNotEmpty() && cause != null && cause in excludedCauses) continue
            // Time-index values are kind-only (1 byte). xyz lives on the spatial value and
            // the record. Skipping null x dropped every match — rollback printed "no matches".
            if (region != null) {
                val x = Records.logKindX(value)
                if (x != null && !region.containsBlock(x, Records.logKindY(value)!!, Records.logKindZ(value)!!)) continue
            }
            dest += Keys.invert(KeyReader.u64(key, 9))
        }
    }
    return world to txns
}

/**
 * Radius -> spatial hour x column (the value carries xyz, so a 6-block cube does not read the
 * whole server). No radius -> time index.
 */
internal fun StorageUnit.gatherForFilter(
    scans: List<Scan>?,
    since: Long?,
    until: Long?,
    excludedCauses: Set<CauseKind>,
    region: LookupRegion?,
): Pair<ArrayList<Long>, ArrayList<Long>> = when {
    scans != null -> gatherBoth(scans, since, until, excludedCauses, region)
    else -> gatherBothByTime(since, until, excludedCauses, region = null)
}

/**
 * Scans covering [region]: one cursor per chunk column, seeked to the newest row the window
 * wants and bounded by [ColumnBound].
 */
internal fun regionScans(
    unit: StorageUnit,
    interning: Interning,
    region: LookupRegion,
    since: Long? = null,
    until: Long? = null,
): List<Scan> {
    val worldId = interning.findWorldId(unit, region.world) ?: return emptyList()
    val bound = ColumnBound(worldId, region.maxChunkZ, since, until)
    val out = ArrayList<Scan>(region.maxChunkX - region.minChunkX + 1)
    for (x in region.minChunkX..region.maxChunkX) {
        out += Scan(
            prefix = Keys.spatialColumnPrefix(worldId, x),
            from = Keys.spatialChunkFrom(worldId, x, region.minChunkZ, until),
            bound = bound,
        )
    }
    return out
}

/**
 * Walks prefixes together, newest sequence first, handing each to [accept] once.
 *
 * Every prefix scan is already descending, so this is a k-way merge and never a sort. Duplicates
 * land adjacent.
 */
internal inline fun StorageUnit.mergeDescending(
    scans: List<Scan>,
    want: LogKind,
    wanted: Int,
    accept: (Long) -> Int,
    sinceMillis: Long? = null,
    untilMillis: Long? = null,
    excludedCauses: Set<CauseKind> = emptySet(),
) {
    val cursors = Array(scans.size) { scan(scans[it].prefix, scans[it].from) }
    try {
        val heads = LongArray(cursors.size)
        // Cursor indices, largest head at the root. Only cursors that still have something are in it
        val heap = IntArray(cursors.size)
        var size = 0
        for (i in cursors.indices) {
            heads[i] = headOf(cursors[i], want, scans[i].bound, sinceMillis, untilMillis, excludedCauses)
            if (heads[i] >= 0) {
                heap[size] = i
                siftUp(heap, heads, size)
                size++
            }
        }

        var previous = -1L
        while (size > 0) {
            val best = heap[0]
            val seq = heads[best]

            heads[best] = headOf(cursors[best], want, scans[best].bound, sinceMillis, untilMillis, excludedCauses)
            if (heads[best] < 0) {
                size--
                if (size > 0) heap[0] = heap[size]
            }
            if (size > 0) siftDown(heap, heads, size)

            if (seq != previous) {
                previous = seq
                if (accept(seq) >= wanted) break
            }
        }
    } finally {
        cursors.forEach { it.close() }
    }
}

/** Moves the entry at [at] up until its parent outranks it. */
internal fun siftUp(heap: IntArray, heads: LongArray, at: Int) {
    var i = at
    while (i > 0) {
        val parent = (i - 1) / 2
        if (heads[heap[i]] <= heads[heap[parent]]) break
        val swap = heap[i]
        heap[i] = heap[parent]
        heap[parent] = swap
        i = parent
    }
}

/** Moves the root down until both children are outranked by it. */
internal fun siftDown(heap: IntArray, heads: LongArray, size: Int) {
    var i = 0
    while (true) {
        val left = i * 2 + 1
        if (left >= size) break
        var largest = if (heads[heap[left]] > heads[heap[i]]) left else i
        val right = left + 1
        if (right < size && heads[heap[right]] > heads[heap[largest]]) largest = right
        if (largest == i) break
        val swap = heap[i]
        heap[i] = heap[largest]
        heap[largest] = swap
        i = largest
    }
}

/**
 * Walks the time index backwards from [until], handing each sequence to [accept] until it reports
 * [wanted] results or the scan falls past [since].
 *
 * Forward through an inverted key is backwards through time.
 */
internal inline fun StorageUnit.scanDescendingByTime(
    want: LogKind,
    since: Long?,
    until: Long?,
    wanted: Int,
    accept: (Long) -> Int,
) {
    val prefix = byteArrayOf(Keys.TIME)
    val from = if (until != null) Keys.timeFrom(until) else prefix
    scan(prefix, from).use { cursor ->
        while (cursor.next()) {
            if (Records.asLogKind(cursor.value()) != want) continue
            val key = cursor.key()
            if (since != null && Keys.invert(KeyReader.u64(key, 1)) < since) break
            if (accept(Keys.invert(KeyReader.u64(key, 9))) >= wanted) break
        }
    }
}

/**
 * The sequences an index handed back, put in the order the record families store them.
 *
 * Sorted rather than assumed: a merge of chunk prefixes comes back strictly descending, but a
 * walk of the time index is ordered by timestamp first, and two records written in the same
 * millisecond say nothing about each other. [fetchAscending] would quietly drop whichever came
 * out of turn.
 */
internal fun ascending(candidates: List<Long>): LongArray {
    val seqs = candidates.toLongArray()
    java.util.Arrays.sort(seqs)
    return seqs
}

/** Newest-first page of an ascending sequence list. Spatial keys are no longer seq-ordered. */
internal fun pageNewest(seqs: LongArray, offset: Int, limit: Int): LongArray {
    val n = seqs.size
    if (n == 0 || offset >= n) return LongArray(0)
    val end = n - offset
    val start = (end - limit).coerceAtLeast(0)
    return if (start == 0 && end == n) seqs else seqs.copyOfRange(start, end)
}

/**
 * Fetches the records for [sorted] in one ordered walk of [tag]'s family, handing each to
 * [accept] oldest-first.
 */
internal inline fun StorageUnit.fetchAscending(
    tag: Byte,
    sorted: LongArray,
    key: (Long) -> ByteArray,
    accept: (Long, java.lang.foreign.MemorySegment) -> Unit,
) {
    if (sorted.isEmpty()) return
    var i = 0
    scan(byteArrayOf(tag), key(sorted[0])).use { cursor ->
        while (i < sorted.size && cursor.next()) {
            val at = KeyReader.u64(cursor.key(), 1)
            // A sequence with no record left, or named twice — skipped rather than waited for
            while (i < sorted.size && sorted[i] < at) i++
            if (i == sorted.size) break
            if (sorted[i] == at) {
                accept(at, cursor.value())
                i++
            }
        }
    }
}

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

private const val MIN_SCAN_BATCH = 64
private const val SCAN_DENSITY = 64
internal const val BATCHED_FROM = 256
internal const val PARALLEL_GETS_FROM = 64

/**
 * The next sequence in [cursor] belonging to [want], or -1 once the cursor is spent — or has run
 * past the end of what [bound] says was wanted.
 */
internal fun headOf(
    cursor: EngineCursor,
    want: LogKind,
    bound: ColumnBound? = null,
    sinceMillis: Long? = null,
    untilMillis: Long? = null,
    excludedCauses: Set<CauseKind> = emptySet(),
): Long {
    while (cursor.next()) {
        val key = cursor.key()
        if (bound != null && bound.past(key)) return -1
        val value = cursor.value()
        if (Records.asLogKind(value) != want) continue
        val at = Records.logKindMillis(value)
        if (sinceMillis != null && at != null && at < sinceMillis) continue
        if (untilMillis != null && at != null && at > untilMillis) continue
        val cause = Records.logKindCause(value)
        if (cause != null && cause.isBookkeeping) continue
        if (excludedCauses.isNotEmpty() && cause != null && cause in excludedCauses) continue
        return Keys.invert(KeyReader.u64(key, key.size - 8))
    }
    return -1
}

/**
 * Loads [seqs] (ascending) newest-first. Parallel past a few hundred: each get is independent
 * against a pinned snapshot, and a rollback of fifty thousand blocks was spending a second
 * walking them in single file while the rest of the CPU sat idle.
 */
@Suppress("UNCHECKED_CAST")
internal inline fun <T : Any> collectNewestFirst(
    seqs: LongArray,
    crossinline load: (Long) -> T?,
): ArrayList<T> {
    val n = seqs.size
    if (n == 0) return ArrayList()
    val slots = arrayOfNulls<Any>(n)
    if (n >= PARALLEL_GETS_FROM) {
        java.util.stream.IntStream.range(0, n).parallel().forEach { i ->
            slots[i] = load(seqs[i])
        }
    } else {
        for (i in 0 until n) slots[i] = load(seqs[i])
    }
    val out = ArrayList<T>(n)
    for (i in n - 1 downTo 0) {
        val hit = slots[i] as T?
        if (hit != null) out += hit
    }
    return out
}
