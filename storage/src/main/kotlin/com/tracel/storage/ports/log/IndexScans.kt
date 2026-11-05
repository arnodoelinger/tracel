package com.tracel.storage.ports.log

import com.tracel.engine.log.lookup.LookupRegion
import com.tracel.storage.StorageUnit
import com.tracel.storage.codec.Tag
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
    val to: ByteArray? = null,
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
    fun past(cursor: EngineCursor): Boolean = Tag.SPATIAL.int(cursor, 2) > lastChunkZ

    /** Older than the window: everything below it in this chunk is older still. */
    fun spent(cursor: EngineCursor): Boolean =
        sinceMillis != null && Tag.SPATIAL.long(cursor, 3) < sinceMillis

    /**
     * Newer than the window. Only the column's first chunk is seeked to `until`; every chunk
     * after it is entered at its own newest row, so each one needs the same jump.
     */
    fun tooNew(cursor: EngineCursor): Boolean =
        untilMillis != null && Tag.SPATIAL.long(cursor, 3) > untilMillis

    /** The window's top edge in the chunk [key] is already inside. */
    fun windowTop(key: ByteArray): ByteArray =
        chunkFrom(worldId, Tag.SPATIAL.int(key, 1), Tag.SPATIAL.int(key, 2), untilMillis)

    /** The top of the chunk after the one [key] names, or null when there is no next chunk. */
    fun nextChunk(key: ByteArray): ByteArray? {
        val z = Tag.SPATIAL.int(key, 2)
        if (z >= lastChunkZ) return null
        return chunkFrom(worldId, Tag.SPATIAL.int(key, 1), z + 1, untilMillis)
    }
}

/** The top of chunk [chunkX], [chunkZ] — or its newest row at or below [untilMillis]. */
internal fun chunkFrom(worldId: Int, chunkX: Int, chunkZ: Int, untilMillis: Long?): ByteArray =
    if (untilMillis == null) Tag.SPATIAL[worldId, chunkX, chunkZ] else Tag.SPATIAL[worldId, chunkX, chunkZ, untilMillis]

/** Scans over whole keys, for indexes where a prefix is the whole story. */
internal fun scansOver(prefixes: List<ByteArray>): List<Scan> = prefixes.map { Scan(it) }

/**
 * [scans] cut into a few pieces per thread, so a region of a dozen columns does not wait on its
 * busiest one while the other threads sit idle.
 */
// TODO: improve this in future
internal fun piecesOf(scans: List<Scan>): List<Scan> {
    val want = Runtime.getRuntime().availableProcessors() * SLICES_PER_THREAD
    val per = maxOf(1, (want + scans.size - 1) / scans.size)
    val out = ArrayList<Scan>(scans.size * per)
    for (scan in scans) {
        val bound = scan.bound
        if (bound == null) {
            out += scan
            continue
        }
        val x = Tag.SPATIAL.int(scan.from, 1)
        val first = Tag.SPATIAL.int(scan.from, 2)
        val chunks = bound.lastChunkZ.toLong() - first + 1
        val parts = minOf(per.toLong(), chunks).toInt().coerceAtLeast(1)
        for (p in 0 until parts) {
            val from = first + (p * chunks / parts).toInt()
            val last = first + ((p + 1) * chunks / parts).toInt() - 1
            out += Scan(
                scan.prefix,
                if (p == 0) scan.from else chunkFrom(bound.worldId, x, from, bound.untilMillis),
                ColumnBound(bound.worldId, last, bound.sinceMillis, bound.untilMillis),
                if (last == Int.MAX_VALUE) null else Tag.SPATIAL[bound.worldId, x, last + 1],
            )
        }
    }
    return out
}

/** Where the time index starts for a window ending at [until]: newest first, so the top. */
internal fun timeTop(until: Long?): ByteArray = if (until != null) Tag.TIME[until] else Tag.TIME.prefix

/** Where the time index stops for a window starting at [since], or null for its end. */
internal fun timeBottom(since: Long?): ByteArray? =
    if (since != null && since > Long.MIN_VALUE) Tag.TIME[since - 1] else null

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
    val bound = ColumnBound(worldId, region.maxTileZ, since, until)
    val out = ArrayList<Scan>(region.maxTileX - region.minTileX + 1)
    for (x in region.minTileX..region.maxTileX) {
        out += Scan(
            prefix = Tag.SPATIAL[worldId, x],
            from = chunkFrom(worldId, x, region.minTileZ, until),
            bound = bound,
        )
    }
    return out
}
