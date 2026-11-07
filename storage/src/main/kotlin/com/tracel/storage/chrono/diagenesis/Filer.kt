package com.tracel.storage.chrono.diagenesis

import com.tracel.storage.Interning
import com.tracel.storage.StorageUnit
import com.tracel.storage.chrono.*
import com.tracel.storage.chrono.capsule.CapsuleView
import com.tracel.storage.codec.Tag
import java.lang.foreign.MemorySegment

/** A stored capsule or fragment: its key, and the view over its bytes. */
internal class Held(val key: ByteArray, val view: CapsuleView) {
    val size: Long get() = view.bytes.byteSize()
    val rows: Int get() = view.rowCount
}

/** Puts capsules on the shelf and takes them off, with every row that finds them. */
internal class Filer(private val interning: Interning) {
    /** [tile]'s capsules on [lane], newest first. */
    fun capsules(unit: StorageUnit, tile: Tile, lane: Lane): List<Held> {
        val out = ArrayList<Held>()
        unit.scan(ChronoKeys.capsules(tile, lane)).use { c -> while (c.next()) out += Held(c.key(), lane.open(c.value())) }
        return out
    }

    /** [tile]'s fragments on [lane], newest first. */
    fun fragments(unit: StorageUnit, tile: Tile, lane: Lane): List<Held> {
        val out = ArrayList<Held>()
        unit.scan(ChronoKeys.fragments(tile, lane)).use { c -> while (c.next()) out += Held(c.key(), lane.open(c.value())) }
        return out
    }

    /**
     * Encodes [rows] — all of one lane, in sequence order — as capsules of at most [max] rows,
     * files them, and says how many bytes that made.
     */
    fun lay(unit: StorageUnit, tile: Tile, lane: Lane, window: Long, rows: Rows, max: Int): Long {
        var written = 0L
        var from = 0
        while (from < rows.size) {
            val until = minOf(rows.size, from + max)
            val part = if (from == 0 && until == rows.size) rows else rows.pick(IntArray(until - from) { from + it })
            val bytes = lane.encode(part, window)
            file(unit, tile, lane, lane.open(MemorySegment.ofArray(bytes)), bytes)
            written += bytes.size
            from = until
        }
        return written
    }

    /** Puts a capsule and every row that finds it. */
    fun file(unit: StorageUnit, tile: Tile, lane: Lane, view: CapsuleView, bytes: ByteArray) {
        ChronoKeys.filing(
            tile, lane, view.firstSeq, view.lastSeq, view.millis()[0],
            TrackIds.of(interning, unit, lane, view), bytes, unit::put,
        )
    }

    /**
     * Deletes a capsule and every row that finds it.
     *
     * The key says which rows: a purge may have trimmed the header.
     */
    fun forget(unit: StorageUnit, tile: Tile, lane: Lane, held: Held) {
        val last = Tag.CHRONO_CAPSULE.long(held.key, 4)
        unit.delete(held.key)
        unit.delete(ChronoKeys.time(last, tile, lane))
        for (id in TrackIds.of(interning, unit, lane, held.view)) if (id != 0) unit.delete(ChronoKeys.track(id, last, tile, lane))
    }
}
