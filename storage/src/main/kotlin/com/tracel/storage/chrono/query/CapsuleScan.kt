package com.tracel.storage.chrono.query

import com.tracel.storage.chrono.Cell
import com.tracel.storage.chrono.Rows
import com.tracel.storage.chrono.Tile
import com.tracel.storage.chrono.capsule.Capsule

/** The sieve, applied to one piece. */
internal object CapsuleScan {
    /** One bit per palette entry; [all] when every one is set and the column need not be read at all. */
    class Mask(val bits: LongArray, val all: Boolean) {
        /** Whether the state id [id] of [palette] has its bit set. */
        fun admits(palette: IntArray, id: Int): Boolean {
            val i = palette.binarySearch(id)
            return i >= 0 && (bits[i ushr 6] ushr i) and 1L != 0L
        }
    }

    /** The rows of [piece] in [tile] that [sieve] lets through, or `null` for none. */
    fun select(piece: Piece, tile: Tile, sieve: Sieve): Rows? {
        if (piece.maxMillis < sieve.since || piece.minMillis > sieve.until) return null
        piece.blocks?.let { return selectSealed(it, tile, sieve) }

        // Other lanes have no column layout to scan; their palettes still rule a capsule out on the header alone
        val capsule = piece.capsule
        if (maskOf(capsule.actors, sieve.actors) == null || maskOf(capsule.kinds, sieve.kinds) == null) return null
        return selectRows(piece.rows(), tile, sieve)
    }

    /** The same questions, asked of plain rows one by one: live pieces are small and recent. */
    fun selectRows(rows: Rows, tile: Tile, sieve: Sieve): Rows? {
        val box = sieve.box
        val wholeBox = box.coversColumns(tile)
        val states = sieve.states
        val selection = IntArray(rows.size)
        var kept = 0
        for (i in 0 until rows.size) {
            val ok = sieve.admitsTime(rows.millis[i]) &&
                sieve.actors.admits(rows.actor[i]) &&
                sieve.kinds.admits(rows.kind[i]) &&
                (wholeBox || box.contains(tile, Cell(rows.cell[i]))) &&
                (states == null || states.admits(rows.before[i]) || states.admits(rows.after[i]))
            selection[kept] = i
            kept += if (ok) 1 else 0
        }
        return when (kept) {
            0 -> null
            rows.size -> rows
            else -> rows.pick(selection, kept)
        }
    }

    /** A palette, turned into one bit per entry by [test]; `null` when no entry passes. */
    fun maskOf(palette: IntArray, test: IdTest): Mask? {
        if (palette.isEmpty()) return null
        val bits = LongArray((palette.size + 63) ushr 6)
        var admitted = 0
        for (i in palette.indices) {
            if (!test.admits(palette[i])) continue
            bits[i ushr 6] = bits[i ushr 6] or (1L shl i)
            admitted++
        }
        if (admitted == 0) return null
        return Mask(bits, admitted == palette.size)
    }

    private fun selectSealed(capsule: Capsule, tile: Tile, sieve: Sieve): Rows? {
        val actorMask = maskOf(capsule.actors, sieve.actors) ?: return null
        val kindMask = maskOf(capsule.kinds, sieve.kinds) ?: return null
        val stateMask = sieve.states?.let { maskOf(capsule.states, it) ?: return null }
        val cellMask = if (sieve.box.coversColumns(tile)) null else cellMaskOf(capsule, tile, sieve.box) ?: return null

        val n = capsule.rowCount
        val selection = IntArray(n)
        var kept = -1

        // A mask that admits every palette entry says nothing; its column is not read. The others are decoded once
        // and tested together, so a capsule that asks about one actor in one box walks its rows a single time.
        val actorIdx = if (actorMask.all) null else capsule.actorIndexes()
        val kindIdx = if (kindMask.all) null else capsule.kindIndexes()
        val cellIdx = if (cellMask == null) null else capsule.cellIndexes()
        if (actorIdx != null || kindIdx != null || cellIdx != null) {
            kept = 0
            for (i in 0 until n) {
                selection[kept] = i
                val ok = (actorIdx == null || bit(actorMask.bits, actorIdx[i])) &&
                        (kindIdx == null || bit(kindMask.bits, kindIdx[i])) &&
                        (cellIdx == null || bit(cellMask!!, cellIdx[i]))
                kept += if (ok) 1 else 0
            }
            if (kept == 0) return null
        }
        val wholeTime = capsule.minMillis >= sieve.since && capsule.maxMillis <= sieve.until
        if (!wholeTime) {
            val millis = capsule.millis()
            if (kept < 0) {
                kept = 0
                for (i in 0 until n) {
                    selection[kept] = i
                    kept += if (sieve.admitsTime(millis[i])) 1 else 0
                }
            } else {
                var out = 0
                for (j in 0 until kept) {
                    val i = selection[j]
                    selection[out] = i
                    out += if (sieve.admitsTime(millis[i])) 1 else 0
                }
                kept = out
            }
            if (kept == 0) return null
        }
        val rows = capsule.rows()
        if (stateMask != null && !stateMask.all) {
            val states = capsule.states
            if (kept < 0) {
                kept = 0
                for (i in 0 until n) {
                    selection[kept] = i
                    kept += if (stateMask.admits(states, rows.before[i]) || stateMask.admits(states, rows.after[i])) 1 else 0
                }
            } else {
                var out = 0
                for (j in 0 until kept) {
                    val i = selection[j]
                    selection[out] = i
                    out += if (stateMask.admits(states, rows.before[i]) || stateMask.admits(states, rows.after[i])) 1 else 0
                }
                kept = out
            }
            if (kept == 0) return null
        }
        return if (kept < 0) rows else rows.pick(selection, kept)
    }

    private fun bit(mask: LongArray, i: Int): Boolean = (mask[i ushr 6] ushr i) and 1L != 0L

    private fun cellMaskOf(capsule: Capsule, tile: Tile, box: Box): LongArray? {
        val cells = capsule.cells
        val bits = LongArray((cells.size + 63) ushr 6)
        var admitted = 0
        for (i in cells.indices) {
            if (!box.contains(tile, Cell(cells[i]))) continue
            bits[i ushr 6] = bits[i ushr 6] or (1L shl i)
            admitted++
        }
        return if (admitted == 0) null else bits
    }
}
