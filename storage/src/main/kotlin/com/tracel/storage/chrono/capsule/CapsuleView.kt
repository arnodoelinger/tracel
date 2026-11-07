package com.tracel.storage.chrono.capsule

import com.tracel.storage.chrono.Cell
import com.tracel.storage.chrono.Lane
import com.tracel.storage.chrono.Rows
import java.lang.foreign.MemorySegment

/** One run of a tile's history, as every query reads it, whichever lane wrote it. */
interface CapsuleView {
    val bytes: MemorySegment
    val lane: Lane
    val rowCount: Int
    val cellCount: Int
    val firstSeq: Long
    val lastSeq: Long
    val minMillis: Long
    val maxMillis: Long
    val window: Long
    val actors: IntArray
    val trackIds: IntArray get() = actors
    val kinds: IntArray
    val cells: IntArray

    /** The index of [cell] in [cells], or -1. */
    fun indexOfCell(cell: Cell): Int

    /** Every row's sequence, ascending. */
    fun seqs(): LongArray

    /** Every row's wall-clock time, in sequence order. */
    fun millis(): LongArray

    /** Every row, whole, in ascending sequence. */
    fun rows(): Rows
}
