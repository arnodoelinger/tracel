package com.tracel.storage.ports.log

import com.tracel.annotations.CauseKind
import com.tracel.annotations.isBookkeeping
import com.tracel.engine.log.LookupRegion
import com.tracel.model.log.LogKind
import com.tracel.storage.StorageUnit
import com.tracel.storage.codec.KeyReader
import com.tracel.storage.codec.Keys
import com.tracel.storage.codec.Records
import com.tracel.storage.intern.Interning
import com.tracel.storage.spi.EngineCursor
import com.tracel.storage.util.siftDown
import com.tracel.storage.util.siftUp

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
    fun past(cursor: EngineCursor): Boolean = Keys.spatialChunkZ(cursor) > lastChunkZ

    /** Older than the window: everything below it in this chunk is older still. */
    fun spent(cursor: EngineCursor): Boolean =
        sinceMillis != null && Keys.spatialMillis(cursor) < sinceMillis

    /**
     * Newer than the window. Only the column's first chunk is seeked to `until`; every chunk
     * after it is entered at its own newest row, so each one needs the same jump.
     */
    fun tooNew(cursor: EngineCursor): Boolean =
        untilMillis != null && Keys.spatialMillis(cursor) > untilMillis

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

/** When this row happened. Be aware that a spatial key already carries the timestamp. */
internal fun millisOf(cursor: EngineCursor, value: java.lang.foreign.MemorySegment): Long? =
    if (cursor.keyLength() > 0 && cursor.keyByte(0) == Keys.SPATIAL) {
        Keys.spatialMillis(cursor)
    } else {
        Records.logKindMillis(value)
    }

/** The sequence a log index row names, which every family parks in the last eight bytes. */
internal fun seqOf(cursor: EngineCursor): Long = Keys.invert(cursor.keyU64(cursor.keyLength() - 8))

/** The world rows a spatial walk kept, with everything the index itself said about each one. */
internal class SpatialRows(capacity: Int = 64) {
    var size: Int = 0; private set
    var seqs = LongArray(capacity); private set
    var millis = LongArray(capacity); private set
    var xs = IntArray(capacity); private set
    var ys = IntArray(capacity); private set
    var zs = IntArray(capacity); private set
    var beforeIds = IntArray(capacity); private set
    var afterIds = IntArray(capacity); private set
    var causedByIds = IntArray(capacity); private set
    var causes = ByteArray(capacity); private set
    var actions = ByteArray(capacity); private set
    var deferred = BooleanArray(capacity); private set

    fun add(
        seq: Long,
        atMillis: Long,
        x: Int,
        y: Int,
        z: Int,
        cause: Byte,
        action: Byte,
        beforeId: Int,
        afterId: Int,
        causedById: Int,
        needsRecord: Boolean,
    ) {
        if (size == seqs.size) grow()
        val i = size
        seqs[i] = seq; millis[i] = atMillis
        xs[i] = x; ys[i] = y; zs[i] = z
        causes[i] = cause; actions[i] = action
        beforeIds[i] = beforeId; afterIds[i] = afterId; causedByIds[i] = causedById
        deferred[i] = needsRecord
        size = i + 1
    }

    fun addAll(other: SpatialRows) {
        reserve(size + other.size)
        val at = size
        System.arraycopy(other.seqs, 0, seqs, at, other.size)
        System.arraycopy(other.millis, 0, millis, at, other.size)
        System.arraycopy(other.xs, 0, xs, at, other.size)
        System.arraycopy(other.ys, 0, ys, at, other.size)
        System.arraycopy(other.zs, 0, zs, at, other.size)
        System.arraycopy(other.beforeIds, 0, beforeIds, at, other.size)
        System.arraycopy(other.afterIds, 0, afterIds, at, other.size)
        System.arraycopy(other.causedByIds, 0, causedByIds, at, other.size)
        System.arraycopy(other.causes, 0, causes, at, other.size)
        System.arraycopy(other.actions, 0, actions, at, other.size)
        System.arraycopy(other.deferred, 0, deferred, at, other.size)
        size = at + other.size
    }

    private fun grow() = reserve(maxOf(seqs.size shl 1, 64))

    /** Room for [n] rows, in one round of copies rather than a doubling apiece. */
    fun reserve(n: Int) {
        if (seqs.size >= n) return
        seqs = seqs.copyOf(n); millis = millis.copyOf(n)
        xs = xs.copyOf(n); ys = ys.copyOf(n); zs = zs.copyOf(n)
        beforeIds = beforeIds.copyOf(n); afterIds = afterIds.copyOf(n)
        causedByIds = causedByIds.copyOf(n)
        causes = causes.copyOf(n); actions = actions.copyOf(n)
        deferred = deferred.copyOf(n)
    }

    /** Row indices ordered by ascending sequence. */
    fun ascendingOrder(): IntArray {
        val n = size
        var keys = seqs.copyOf(n)
        var order = IntArray(n) { it }
        var keysInto = LongArray(n)
        var orderInto = IntArray(n)
        val counts = IntArray(257)
        for (shift in 0 until 64 step 8) {
            java.util.Arrays.fill(counts, 0)
            for (i in 0 until n) counts[(((keys[i] ushr shift) and 0xFF).toInt()) + 1]++
            var carried = 0
            var nonEmpty = 0
            for (b in 1..256) {
                if (counts[b] != 0) nonEmpty++
                carried += counts[b]
                counts[b] = carried
            }
            // Every row shares this byte; the pass would be a straight copy
            if (nonEmpty <= 1) continue
            for (i in 0 until n) {
                val key = keys[i]
                val at = counts[((key ushr shift) and 0xFF).toInt()]++
                keysInto[at] = key
                orderInto[at] = order[i]
            }
            val swapKeys = keys; keys = keysInto; keysInto = swapKeys
            val swapOrder = order; order = orderInto; orderInto = swapOrder
        }
        return order
    }
}

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
    worldRows: SpatialRows? = null,
) {
    var rows = 0L
    var kept = 0L
    scan(scan.prefix, scan.from).use { cursor ->
        val bound = scan.bound
        while (cursor.next()) {
            if (bound != null) {
                if (bound.past(cursor)) break
                if (bound.tooNew(cursor)) {
                    cursor.skipTo(bound.windowTop(cursor.key()))
                    continue
                }
                if (bound.spent(cursor)) {
                    cursor.skipTo(bound.nextChunk(cursor.key()) ?: break)
                    continue
                }
            }
            rows++
            val value = cursor.value()
            val kind = Records.asLogKind(value)
            val dest = when (kind) {
                LogKind.WORLD -> worldOut
                LogKind.TRANSACTION -> txnOut
            } ?: continue
            val at = millisOf(cursor, value)
            if (sinceMillis != null && at != null && at < sinceMillis) {
                if (bound == null && at < sinceMillis - SEQ_TIME_SLACK_MILLIS) break
                continue
            }
            if (untilMillis != null && at != null && at > untilMillis) continue
            val cause = Records.logKindCause(value)
            if (cause != null && cause.isBookkeeping) continue
            if (excludedCauses.isNotEmpty() && cause != null && cause in excludedCauses) continue
            if (region != null && kind == LogKind.WORLD) {
                val x = Records.logKindX(value)
                if (x != null && !region.containsBlock(
                        x,
                        Records.logKindY(value)!!,
                        Records.logKindZ(value)!!
                    )
                ) continue
            }
            kept++
            val seq = seqOf(cursor)
            dest += seq
            if (worldRows == null || kind != LogKind.WORLD) continue
            // The row is read here. Or not at all
            val inline = Records.logKindHasInline(value)
            worldRows.add(
                seq,
                at ?: 0L,
                Records.logKindX(value) ?: 0,
                Records.logKindY(value) ?: 0,
                Records.logKindZ(value) ?: 0,
                (cause?.ordinal ?: 0).toByte(),
                if (inline) Records.logKindAction(value).ordinal.toByte() else 0,
                if (inline) Records.logKindBefore(value) else 0,
                if (inline) Records.logKindAfter(value) else 0,
                if (inline) Records.logKindCausedBy(value) else 0,
                needsRecord = !inline,
            )
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
    worldRows: SpatialRows? = null,
): Pair<ArrayList<Long>, ArrayList<Long>> {
    if (scans.isEmpty()) return ArrayList<Long>() to ArrayList()
    if (scans.size == 1) {
        val world = ArrayList<Long>()
        val txns = ArrayList<Long>()
        walkColumn(scans[0], since, until, excludedCauses, region, world, txns, worldRows)
        return world to txns
    }
    val worlds = arrayOfNulls<ArrayList<Long>>(scans.size)
    val txns = arrayOfNulls<ArrayList<Long>>(scans.size)
    val rows = arrayOfNulls<SpatialRows>(scans.size)
    java.util.stream.IntStream.range(0, scans.size).parallel().forEach { i ->
        val world = ArrayList<Long>()
        val txn = ArrayList<Long>()
        val part = if (worldRows == null) null else SpatialRows()
        walkColumn(scans[i], since, until, excludedCauses, region, world, txn, part)
        worlds[i] = world
        txns[i] = txn
        rows[i] = part
    }
    var wN = 0
    var tN = 0
    var rN = 0
    for (i in scans.indices) {
        wN += worlds[i]!!.size
        tN += txns[i]!!.size
        rN += rows[i]?.size ?: 0
    }
    worldRows?.reserve(rN)
    val world = ArrayList<Long>(wN)
    val txn = ArrayList<Long>(tN)
    for (i in scans.indices) {
        world.addAll(worlds[i]!!)
        txn.addAll(txns[i]!!)
        worldRows?.addAll(rows[i]!!)
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
            if (region != null) {
                val x = Records.logKindX(value)
                if (x != null && !region.containsBlock(
                        x,
                        Records.logKindY(value)!!,
                        Records.logKindZ(value)!!
                    )
                ) continue
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
    worldRows: SpatialRows? = null,
): Pair<ArrayList<Long>, ArrayList<Long>> = when {
    scans != null -> gatherBoth(scans, since, until, excludedCauses, region, worldRows)
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
            if (since != null && Keys.invert(cursor.keyU64(1)) < since) break
            if (Records.asLogKind(cursor.value()) != want) continue
            if (accept(Keys.invert(cursor.keyU64(9))) >= wanted) break
        }
    }
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
            val at = cursor.keyU64(1)
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

/** Walks the [tag] family for the keys in `sorted[from until to]`, one cursor for all of them. */
internal inline fun StorageUnit.walkWanted(
    tag: Byte,
    sorted: LongArray,
    from: Int,
    to: Int,
    keyOf: (Long) -> ByteArray,
    row: (Long, EngineCursor) -> Unit,
) {
    if (from >= to) return
    var i = from
    scan(byteArrayOf(tag), keyOf(sorted[from])).use { cursor ->
        while (i < to && cursor.next()) {
            val at = cursor.keyU64(1)
            while (i < to && sorted[i] < at) i++
            if (i == to) return
            if (sorted[i] == at) {
                row(at, cursor)
                continue
            }
            if (sorted[i] - at > SKIP_GAP) cursor.skipTo(keyOf(sorted[i]))
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

private const val MIN_SCAN_BATCH = 64
private const val SKIP_GAP = 64L
private const val SCAN_DENSITY = 64
private const val SEQ_TIME_SLACK_MILLIS = 5 * 60_000L

internal const val BATCHED_FROM = 256

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
        if (bound != null && bound.past(cursor)) return -1
        val value = cursor.value()
        if (Records.asLogKind(value) != want) continue
        val at = millisOf(cursor, value)
        if (sinceMillis != null && at != null && at < sinceMillis) {
            if (bound == null && at < sinceMillis - SEQ_TIME_SLACK_MILLIS) return -1
            continue
        }
        if (untilMillis != null && at != null && at > untilMillis) continue
        val cause = Records.logKindCause(value)
        if (cause != null && cause.isBookkeeping) continue
        if (excludedCauses.isNotEmpty() && cause != null && cause in excludedCauses) continue
        return seqOf(cursor)
    }
    return -1
}
