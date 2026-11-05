package com.tracel.storage.ports.log

import com.tracel.storage.codec.Index
import com.tracel.engine.log.lookup.LookupRegion
import com.tracel.model.cause.CauseKind
import com.tracel.model.log.LogKind
import com.tracel.storage.StorageUnit
import com.tracel.storage.codec.Keys
import com.tracel.storage.codec.Tag
import com.tracel.storage.spi.EngineCursor
import com.tracel.storage.util.eachTask
import com.tracel.storage.util.siftDown
import com.tracel.storage.util.siftUp
import it.unimi.dsi.fastutil.longs.LongArrayList
import java.lang.foreign.MemorySegment
import java.util.stream.IntStream

private const val SEQ_TIME_SLACK_MILLIS = 5 * 60_000L

internal const val BATCHED_FROM = 256

/** When this row happened. Be aware that a spatial key already carries the timestamp. */
internal fun millisOf(cursor: EngineCursor, value: MemorySegment): Long? =
    if (cursor.keyLength() > 0 && cursor.keyByte(0) == Tag.SPATIAL.byte) {
        Tag.SPATIAL.long(cursor, 3)
    } else {
        Index.millis(value)
    }

/** The sequence a log index row names, which every family parks in the last eight bytes. */
internal fun seqOf(cursor: EngineCursor): Long = Keys.invert(cursor.keyU64(cursor.keyLength() - 8))

/** Sequences of [kind] a large query will fetch. */
internal fun StorageUnit.gatherSeqs(
    kind: LogKind,
    scans: List<Scan>?,
    since: Long?,
    until: Long?,
    wanted: Int,
    excludedCauses: Set<CauseKind> = emptySet(),
    region: LookupRegion? = null,
): LongArrayList {
    val out = LongArrayList()
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
        val parts = arrayOfNulls<LongArrayList>(scans.size)
        IntStream.range(0, scans.size).parallel().forEach { i ->
            val part = LongArrayList()
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

    val drive = { seq: Long -> out.add(seq); out.size }
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
    worldOut: LongArrayList?,
    txnOut: LongArrayList?,
    worldRows: SpatialRows? = null,
) {
    var rows = 0L
    var kept = 0L
    scan(scan.prefix, scan.from, scan.to).use { cursor ->
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
            val kind = Index.kind(value)
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
            val cause = Index.cause(value)
            if (cause != null && cause.isBookkeeping) continue
            if (excludedCauses.isNotEmpty() && cause != null && cause in excludedCauses) continue
            if (region != null && kind == LogKind.WORLD) {
                val x = Index.x(value)
                if (x != null && !region.containsBlock(
                        x,
                        Index.y(value)!!,
                        Index.z(value)!!
                    )
                ) continue
            }
            kept++
            val seq = seqOf(cursor)
            dest.add(seq)
            if (worldRows == null || kind != LogKind.WORLD) continue
            // The row is read here. Or not at all
            val inline = Index.hasInline(value)
            worldRows.add(
                seq,
                at ?: 0L,
                Index.x(value) ?: 0,
                Index.y(value) ?: 0,
                Index.z(value) ?: 0,
                (cause?.ordinal ?: 0).toByte(),
                if (inline) Index.action(value).ordinal.toByte() else 0,
                if (inline) Index.before(value) else 0,
                if (inline) Index.after(value) else 0,
                if (inline) Index.causedBy(value) else 0,
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
): Pair<LongArrayList, LongArrayList> {
    if (scans.isEmpty()) return LongArrayList() to LongArrayList()
    val pieces = piecesOf(scans)
    if (pieces.size == 1) {
        val world = LongArrayList()
        val txns = LongArrayList()
        walkColumn(pieces[0], since, until, excludedCauses, region, world, txns, worldRows)
        return world to txns
    }
    val worlds = arrayOfNulls<LongArrayList>(pieces.size)
    val txns = arrayOfNulls<LongArrayList>(pieces.size)
    val rows = arrayOfNulls<SpatialRows>(pieces.size)
    eachTask(pieces.size) { i ->
        val world = LongArrayList()
        val txn = LongArrayList()
        val part = if (worldRows == null) null else SpatialRows()
        walkColumn(pieces[i], since, until, excludedCauses, region, world, txn, part)
        worlds[i] = world
        txns[i] = txn
        rows[i] = part
    }
    var wN = 0
    var tN = 0
    var rN = 0
    for (i in pieces.indices) {
        wN += worlds[i]!!.size
        tN += txns[i]!!.size
        rN += rows[i]?.size ?: 0
    }
    worldRows?.reserve(rN)
    val world = LongArrayList(wN)
    val txn = LongArrayList(tN)
    for (i in pieces.indices) {
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
): Pair<LongArrayList, LongArrayList> {
    val world = LongArrayList()
    val txns = LongArrayList()
    walkTime(timeTop(until), null, since, excludedCauses, region, world, txns)
    return world to txns
}

/** One stretch of the time index, [from] up to [to], sorted into world and transaction sequences. */
internal fun StorageUnit.walkTime(
    from: ByteArray,
    to: ByteArray?,
    since: Long?,
    excludedCauses: Set<CauseKind>,
    region: LookupRegion?,
    world: LongArrayList,
    txns: LongArrayList,
) {
    scan(Tag.TIME.prefix, from, to).use { cursor ->
        while (cursor.next()) {
            if (since != null && Keys.invert(cursor.keyU64(1)) < since) break
            val value = cursor.value()
            val dest = when (Index.kind(value)) {
                LogKind.WORLD -> world
                LogKind.TRANSACTION -> txns
            }
            val cause = Index.cause(value)
            if (cause != null && cause.isBookkeeping) continue
            if (excludedCauses.isNotEmpty() && cause != null && cause in excludedCauses) continue
            if (region != null) {
                val x = Index.x(value)
                if (x != null && !region.containsBlock(
                        x,
                        Index.y(value)!!,
                        Index.z(value)!!
                    )
                ) continue
            }
            dest.add(Keys.invert(cursor.keyU64(9)))
        }
    }
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
): Pair<LongArrayList, LongArrayList> = when {
    scans != null -> gatherBoth(scans, since, until, excludedCauses, region, worldRows)
    else -> gatherBothByTime(since, until, excludedCauses, region = null)
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
    val prefix = Tag.TIME.prefix
    val from = if (until != null) Tag.TIME[until] else prefix
    scan(prefix, from).use { cursor ->
        while (cursor.next()) {
            if (since != null && Keys.invert(cursor.keyU64(1)) < since) break
            if (Index.kind(cursor.value()) != want) continue
            if (accept(Keys.invert(cursor.keyU64(9))) >= wanted) break
        }
    }
}

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
        if (Index.kind(value) != want) continue
        val at = millisOf(cursor, value)
        if (sinceMillis != null && at != null && at < sinceMillis) {
            if (bound == null && at < sinceMillis - SEQ_TIME_SLACK_MILLIS) return -1
            continue
        }
        if (untilMillis != null && at != null && at > untilMillis) continue
        val cause = Index.cause(value)
        if (cause != null && cause.isBookkeeping) continue
        if (excludedCauses.isNotEmpty() && cause != null && cause in excludedCauses) continue
        return seqOf(cursor)
    }
    return -1
}
