package com.tracel.storage.chrono.query

import com.tracel.storage.chrono.Cell
import com.tracel.storage.chrono.Lane
import com.tracel.storage.chrono.Rows
import com.tracel.storage.chrono.Tile
import com.tracel.storage.chrono.hash.IntIndex
import com.tracel.storage.chrono.sameExtras

/** Every question chrono answers, asked of the capsules a [source] holds. */
class ChronoReader(source: CapsuleSource, slackMillis: Long = SLACK_MILLIS) {
    private val gather = Gatherer(source, slackMillis)

    /** What happened at [cell] of [tile] on [lane], the newest [limit] changes, in ascending sequence. */
    fun history(tile: Tile, cell: Cell, limit: Int = Int.MAX_VALUE, lane: Lane = Lane.BLOCKS): Rows {
        val pieces = gather.tile(tile, lane, Long.MIN_VALUE)
        val parts = ArrayList<Rows>()
        var have = 0
        for (i in pieces.indices.reversed()) {
            if (have >= limit) break
            val piece = pieces[i]
            if (piece.capsule.indexOfCell(cell) < 0) continue
            val rows = piece.rows()
            val at = IntArray(rows.size)
            var k = 0
            for (r in 0 until rows.size) {
                at[k] = r
                k += if (rows.cell[r] == cell.packed) 1 else 0
            }
            if (k == 0) continue
            parts += rows.pick(at, k)
            have += k
        }
        val all = Rows.concat(parts).sorted()
        if (all.size <= limit) return all
        return all.pick(IntArray(limit) { all.size - limit + it })
    }

    /** Every change on [lane] in [tiles] that [sieve] lets through, by tile, each tile's in ascending sequence. */
    fun select(tiles: Collection<Tile>, sieve: Sieve, lane: Lane = Lane.BLOCKS): List<Found> {
        val out = ArrayList<Found>()
        for (tile in tiles) {
            val parts = ArrayList<Rows>()
            for (piece in gather.tile(tile, lane, sieve.since)) CapsuleScan.select(piece, tile, sieve)?.let { parts += it }
            if (parts.isNotEmpty()) out += Found(tile, Rows.concat(parts).sorted())
        }
        return out
    }

    /**
     * Every change on [lane] anywhere that [sieve] lets through, found by walking capsules newest first: those
     * [actor] is in, or every capsule with [actor] `null`.
     */
    fun selectAnywhere(actor: Int?, sieve: Sieve, lane: Lane = Lane.BLOCKS): List<Found> {
        val byTile = LinkedHashMap<Long, ArrayList<Rows>>()
        for ((tile, piece) in gather.walk(actor, lane, sieve.since)) {
            val rows = CapsuleScan.select(piece, tile, sieve) ?: continue
            byTile.getOrPut(tile.packed) { ArrayList() } += rows
        }
        return byTile.map { (packed, parts) -> Found(Tile(packed), Rows.concat(parts).sorted()) }
    }

    /**
     * The oldest and the newest change [sieve] lets through of every block cell,
     * or of every entity, in [tiles] — all a rollback planner reads.
     *
     * One changed once gives one row.
     */
    fun ends(tiles: Collection<Tile>, sieve: Sieve, lane: Lane = Lane.BLOCKS): List<Found> =
        endsOf(select(tiles, sieve, lane), lane)

    /**
     * Keeps the oldest and newest row of every block cell, or of every entity.
     *
     * Entities move between tiles, so their ends are taken over everything found,
     * not tile by tile.
     */
    fun endsOf(found: List<Found>, lane: Lane): List<Found> {
        if (lane == Lane.BLOCKS) return found.map { blockEnds(it) }
        return entityEnds(found)
    }

    /**
     * Every block cell of [tiles] inside [box] that changed at or after [since]:
     * what stood there just before, and what stands there now.
     */
    fun rewind(tiles: Collection<Tile>, since: Long, box: Box = Box.EVERYWHERE): List<Rewound> =
        tiles.mapNotNull { rewindTile(it, since, box) }

    /**
     * One [rewind] per moment of [times]: frames of a timelapse,
     * each the world as it stood at that moment.
     */
    fun frames(tiles: Collection<Tile>, times: LongArray, box: Box = Box.EVERYWHERE): List<List<Rewound>> =
        times.map { rewind(tiles, it, box) }

    /**
     * Block changes in [tiles] inside [box], at or after [since],
     * that did not start where the change before them left the cell — inside one capsule or
     * across the seam between two.
     */
    fun gaps(tiles: Collection<Tile>, since: Long, box: Box = Box.EVERYWHERE): List<Gaps> =
        tiles.mapNotNull { gapsOf(it, since, box) }

    private fun blockEnds(found: Found): Found {
        val rows = found.rows
        val cells = IntIndex(rows.size)
        var oldest = IntArray(16)
        var newest = IntArray(16)
        for (r in 0 until rows.size) {
            val known = cells.size
            val c = cells.add(rows.cell[r])
            if (c == oldest.size) {
                oldest = oldest.copyOf(c shl 1)
                newest = newest.copyOf(c shl 1)
            }
            if (c == known) oldest[c] = r
            newest[c] = r
        }
        val keep = BooleanArray(rows.size)
        for (c in 0 until cells.size) {
            keep[oldest[c]] = true
            keep[newest[c]] = true
        }
        return Found(found.tile, rows.pick(keepers(keep), keep.count { it }))
    }

    private fun entityEnds(found: List<Found>): List<Found> {
        class End(var oldSeq: Long, var oldTile: Int, var oldRow: Int, var newSeq: Long, var newTile: Int, var newRow: Int)
        val ends = HashMap<Pair<Long, Long>, End>()
        for ((t, f) in found.withIndex()) {
            val rows = f.rows
            val high = rows.uuidHigh ?: continue
            val low = rows.uuidLow ?: continue
            for (r in 0 until rows.size) {
                val seq = rows.seq[r]
                val e = ends.getOrPut(high[r] to low[r]) { End(seq, t, r, seq, t, r) }
                if (seq < e.oldSeq) { e.oldSeq = seq; e.oldTile = t; e.oldRow = r }
                if (seq > e.newSeq) { e.newSeq = seq; e.newTile = t; e.newRow = r }
            }
        }
        val keep = found.map { BooleanArray(it.rows.size) }
        for (e in ends.values) {
            keep[e.oldTile][e.oldRow] = true
            keep[e.newTile][e.newRow] = true
        }
        return found.indices.mapNotNull { t ->
            val n = keep[t].count { it }
            if (n == 0) null else Found(found[t].tile, found[t].rows.pick(keepers(keep[t]), n))
        }
    }

    private fun keepers(keep: BooleanArray): IntArray {
        val at = IntArray(keep.size)
        var k = 0
        for (r in keep.indices) {
            at[k] = r
            k += if (keep[r]) 1 else 0
        }
        return at
    }

    private fun rewindTile(tile: Tile, since: Long, box: Box): Rewound? {
        val pieces = gather.tile(tile, Lane.BLOCKS, since)
        if (pieces.isEmpty()) return null
        val overlapping = (1 until pieces.size).any { pieces[it].firstSeq <= pieces[it - 1].lastSeq }
        if (!overlapping) return rewindMerging(tile, pieces, since, box)
        val wholeBox = box.coversColumns(tile)
        val ledger = CellLedger()
        for (piece in pieces) {
            if (piece.maxMillis < since) continue
            val rows = piece.rows()
            for (r in 0 until rows.size) {
                if (rows.millis[r] < since) continue
                if (!wholeBox && !box.contains(tile, Cell(rows.cell[r]))) continue
                ledger.offer(
                    rows.cell[r], rows.seq[r], rows.before[r], rows.beforeExtrasAt(r), rows.seq[r], rows.after[r], rows.afterExtrasAt(r),
                )
            }
        }
        return ledger.toRewound(tile)
    }

    /** [rewind] of a tile whose capsules never interleave, as a merge. */
    private fun rewindMerging(tile: Tile, pieces: List<Piece>, since: Long, box: Box): Rewound? {
        val wholeBox = box.coversColumns(tile)
        var acc: Summary? = null
        var spare: Summary? = null
        for (i in pieces.indices.reversed()) {
            val piece = pieces[i]
            if (piece.maxMillis < since) continue
            val blocks = piece.blocks
            val summary = if (blocks != null && blocks.minMillis >= since) {
                Summary.ofCells(blocks, tile, box, wholeBox)
            } else {
                Summary.ofRows(piece.rows(), since, tile, box, wholeBox)
            } ?: continue
            if (acc == null) {
                acc = summary
                continue
            }
            val merged = acc.mergeOlder(summary, spare)
            spare = if (acc.owned) acc else null
            acc = merged
        }
        return acc?.toRewound(tile)
    }

    private fun gapsOf(tile: Tile, since: Long, box: Box): Gaps? {
        val pieces = gather.tile(tile, Lane.BLOCKS, since)
        if (pieces.isEmpty()) return null
        val all = Rows.concat(pieces.map { it.rows() }).sorted()
        val wholeBox = box.coversColumns(tile)
        val cells = IntIndex(64)
        var state = IntArray(64)
        var extras = arrayOfNulls<ByteArray>(64)
        val seq = LongArray(all.size)
        val millis = LongArray(all.size)
        val cell = IntArray(all.size)
        val expected = IntArray(all.size)
        val found = IntArray(all.size)
        var n = 0
        for (r in 0 until all.size) {
            val before = cells.size
            val c = cells.add(all.cell[r])
            if (c >= state.size) {
                state = state.copyOf(state.size shl 1)
                extras = extras.copyOf(extras.size shl 1)
            }
            val known = c < before
            if (known && (state[c] != all.before[r] || !sameExtras(extras[c], all.beforeExtrasAt(r))) &&
                all.millis[r] >= since && (wholeBox || box.contains(tile, Cell(all.cell[r])))
            ) {
                seq[n] = all.seq[r]
                millis[n] = all.millis[r]
                cell[n] = all.cell[r]
                expected[n] = state[c]
                found[n] = all.before[r]
                n++
            }
            state[c] = all.after[r]
            extras[c] = all.afterExtrasAt(r)
        }
        if (n == 0) return null
        return Gaps(tile, n, seq, millis, cell, expected, found)
    }

    companion object {
        const val SLACK_MILLIS: Long = 5 * 60_000L
    }
}

private class CellLedger {
    private val cells = IntIndex(256)
    private var thenSeq = LongArray(256)
    private var then = IntArray(256)
    private var thenExtras = arrayOfNulls<ByteArray>(256)
    private var nowSeq = LongArray(256)
    private var now = IntArray(256)
    private var nowExtras = arrayOfNulls<ByteArray>(256)

    fun offer(cell: Int, beforeSeq: Long, before: Int, beforeX: ByteArray?, afterSeq: Long, after: Int, afterX: ByteArray?) {
        val known = cells.size
        val c = cells.add(cell)
        if (c == thenSeq.size) grow()
        if (c == known) {
            thenSeq[c] = beforeSeq; then[c] = before; thenExtras[c] = beforeX
            nowSeq[c] = afterSeq; now[c] = after; nowExtras[c] = afterX
            return
        }
        if (beforeSeq < thenSeq[c]) {
            thenSeq[c] = beforeSeq; then[c] = before; thenExtras[c] = beforeX
        }
        if (afterSeq > nowSeq[c]) {
            nowSeq[c] = afterSeq; now[c] = after; nowExtras[c] = afterX
        }
    }

    fun toRewound(tile: Tile): Rewound? {
        val n = cells.size
        if (n == 0) return null
        val order = cells.ranked()
        val byRank = IntArray(n)
        for (i in 0 until n) byRank[order.rank[i]] = i
        return Rewound(
            tile, n, order.sorted,
            IntArray(n) { then[byRank[it]] }, Array(n) { thenExtras[byRank[it]] },
            IntArray(n) { now[byRank[it]] }, Array(n) { nowExtras[byRank[it]] },
        )
    }

    private fun grow() {
        val size = thenSeq.size shl 1
        thenSeq = thenSeq.copyOf(size); then = then.copyOf(size); thenExtras = thenExtras.copyOf(size)
        nowSeq = nowSeq.copyOf(size); now = now.copyOf(size); nowExtras = nowExtras.copyOf(size)
    }
}
