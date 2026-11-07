package com.tracel.storage.chrono.query

import com.tracel.storage.chrono.Cell
import com.tracel.storage.chrono.Rows
import com.tracel.storage.chrono.Tile
import com.tracel.storage.chrono.capsule.Capsule
import com.tracel.storage.chrono.hash.IntIndex

/** Summary. Per cell, sorted by cell: the state a piece found there first and the state it left there last. */
internal class Summary(
    val size: Int,
    val cells: IntArray,
    val entry: IntArray,
    val exit: IntArray,
    val entryExtras: Array<ByteArray?>?,
    val exitExtras: Array<ByteArray?>?,
    val owned: Boolean = false,
) {
    /**
     * This summary, newer, merged with an [older] one: the union of their cells,
     * each keeping the older entry and the newer exit.
     *
     * Two sorted runs, one linear pass.
     */
    fun mergeOlder(older: Summary, spare: Summary? = null): Summary {
        val cap = size + older.size
        val extras = entryExtras != null || exitExtras != null || older.entryExtras != null || older.exitExtras != null
        val reuse = spare != null && spare.cells.size >= cap && (!extras || spare.entryExtras != null)
        val cells = if (reuse) spare.cells else IntArray(cap + (cap ushr 1))
        val entry = if (reuse) spare.entry else IntArray(cells.size)
        val exit = if (reuse) spare.exit else IntArray(cells.size)
        val entryX = if (!extras) null else if (reuse) spare.entryExtras else arrayOfNulls(cells.size)
        val exitX = if (!extras) null else if (reuse) spare.exitExtras else arrayOfNulls(cells.size)
        var i = 0
        var j = 0
        var n = 0
        while (i < size || j < older.size) {
            val a = if (i < size) this.cells[i] else Int.MAX_VALUE
            val b = if (j < older.size) older.cells[j] else Int.MAX_VALUE
            when {
                // Int.MAX_VALUE is a real cell key in theory; the counters, not the sentinel, decide who is done
                j >= older.size || (i < size && a < b) -> {
                    cells[n] = a; entry[n] = this.entry[i]; exit[n] = this.exit[i]
                    entryX?.set(n, entryExtras?.get(i)); exitX?.set(n, exitExtras?.get(i))
                    i++
                }
                i >= size || b < a -> {
                    cells[n] = b; entry[n] = older.entry[j]; exit[n] = older.exit[j]
                    entryX?.set(n, older.entryExtras?.get(j)); exitX?.set(n, older.exitExtras?.get(j))
                    j++
                }
                else -> {
                    cells[n] = a; entry[n] = older.entry[j]; exit[n] = this.exit[i]
                    entryX?.set(n, older.entryExtras?.get(j)); exitX?.set(n, exitExtras?.get(i))
                    i++
                    j++
                }
            }
            n++
        }
        return Summary(n, cells, entry, exit, entryX, exitX, owned = true)
    }

    /** To [Rewound], which is what a rollback to a moment sees, cell by cell. */
    fun toRewound(tile: Tile): Rewound = Rewound(
        tile, size, cells.copyOf(size),
        entry.copyOf(size), Array(size) { entryExtras?.get(it) },
        exit.copyOf(size), Array(size) { exitExtras?.get(it) },
    )

    companion object {
        fun ofCells(capsule: Capsule, tile: Tile, box: Box, wholeBox: Boolean): Summary? {
            val cells = capsule.cells
            val entries = capsule.entries()
            val exits = capsule.exits()
            val extras = capsule.hasExtras
            if (wholeBox) {
                return Summary(
                    cells.size, cells, entries, exits,
                    if (extras) Array(cells.size) { capsule.entryExtras(it) } else null,
                    if (extras) Array(cells.size) { capsule.exitExtras(it) } else null,
                )
            }
            val keep = IntArray(cells.size)
            var n = 0
            for (c in cells.indices) {
                keep[n] = c
                n += if (box.contains(tile, Cell(cells[c]))) 1 else 0
            }
            if (n == 0) return null
            return Summary(
                n, IntArray(n) { cells[keep[it]] }, IntArray(n) { entries[keep[it]] }, IntArray(n) { exits[keep[it]] },
                if (extras) Array(n) { capsule.entryExtras(keep[it]) } else null,
                if (extras) Array(n) { capsule.exitExtras(keep[it]) } else null,
            )
        }

        fun ofRows(rows: Rows, since: Long, tile: Tile, box: Box, wholeBox: Boolean): Summary? {
            val index = IntIndex(64)
            var entry = IntArray(64)
            var exit = IntArray(64)
            val extras = rows.beforeExtras != null || rows.afterExtras != null
            var entryX = if (extras) arrayOfNulls<ByteArray>(64) else null
            var exitX = if (extras) arrayOfNulls<ByteArray>(64) else null
            for (r in 0 until rows.size) {
                if (rows.millis[r] < since) continue
                if (!wholeBox && !box.contains(tile, Cell(rows.cell[r]))) continue
                val known = index.size
                val c = index.add(rows.cell[r])
                if (c == entry.size) {
                    entry = entry.copyOf(c shl 1)
                    exit = exit.copyOf(c shl 1)
                    entryX = entryX?.copyOf(c shl 1)
                    exitX = exitX?.copyOf(c shl 1)
                }
                if (c == known) {
                    entry[c] = rows.before[r]
                    entryX?.set(c, rows.beforeExtrasAt(r))
                }
                exit[c] = rows.after[r]
                exitX?.set(c, rows.afterExtrasAt(r))
            }
            val n = index.size
            if (n == 0) return null
            val ranked = index.ranked()
            val byRank = IntArray(n)
            for (i in 0 until n) byRank[ranked.rank[i]] = i
            return Summary(
                n, ranked.sorted, IntArray(n) { entry[byRank[it]] }, IntArray(n) { exit[byRank[it]] },
                entryX?.let { x -> Array(n) { x[byRank[it]] } }, exitX?.let { x -> Array(n) { x[byRank[it]] } },
            )
        }
    }
}
