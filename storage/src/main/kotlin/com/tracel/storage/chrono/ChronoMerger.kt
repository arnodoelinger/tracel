package com.tracel.storage.chrono

import com.tracel.storage.StorageUnit
import com.tracel.storage.chrono.capsule.CapsuleView
import com.tracel.storage.codec.Tag
import com.tracel.storage.intern.Interning
import java.lang.foreign.MemorySegment
import java.util.concurrent.ConcurrentHashMap

/** Folds fragments into capsules: chrono's own compaction, one tile and lane at a time. */
internal class ChronoMerger(private val settings: ChronoSettings, private val interning: Interning) {
    private class Dirty(val since: Long, val fragments: Int, val urgent: Boolean)

    private val dirty = ConcurrentHashMap<Pair<Long, Lane>, Dirty>()

    @Volatile
    var merges: Long = 0L; private set

    @Volatile
    var mergedBytes: Long = 0L; private set

    /**
     * Marks [tile] on [lane] dirty, so a pass at `now` will merge it if it has enough fragments
     * or is old enough.
     */
    fun dirty(tile: Tile, lane: Lane) {
        dirty.merge(tile.packed to lane, Dirty(settings.clock(), 1, false)) { old, _ -> Dirty(old.since, old.fragments + 1, old.urgent) }
    }

    /** Marks [tile]'s fragments for the next pass, however young: the tile went quiet. */
    fun urgent(tile: Tile) {
        for (lane in Lane.entries) dirty.computeIfPresent(tile.packed to lane) { _, d -> Dirty(d.since, d.fragments, true) }
    }

    /** Tiles waiting, and how many fragments they hold, as far as this merger knows. */
    fun backlog(): Int = dirty.values.sumOf { it.fragments }

    /** Notes every fragment already on disk: whatever a crash or a shutdown left unmerged. */
    fun discover(unit: StorageUnit) {
        unit.scan(Tag.CHRONO_FRAGMENT.prefix).use { cursor ->
            while (cursor.next()) {
                val tile = Tile.of(Tag.CHRONO_FRAGMENT.int(cursor, 0), Tag.CHRONO_FRAGMENT.int(cursor, 1), Tag.CHRONO_FRAGMENT.int(cursor, 2))
                dirty(tile, Lane.of(Tag.CHRONO_FRAGMENT.u8(cursor, 3)))
            }
        }
    }

    /** The tiles a pass at [now] should merge, at most [limit]; every one with [all]. */
    fun due(now: Long, all: Boolean, limit: Int): List<Pair<Tile, Lane>> {
        val out = ArrayList<Pair<Tile, Lane>>()
        for ((key, d) in dirty) {
            if (out.size >= limit) break
            if (all || d.urgent || d.fragments >= settings.mergeFragments || now - d.since >= settings.mergeAfterMillis) {
                out += Tile(key.first) to key.second
            }
        }
        return out
    }

    /**
     * Merges [tile]'s fragments on [lane] in [unit]. Says how many rows went in; the dirty mark goes once the unit
     * commits, and stays if it does not.
     */
    fun merge(unit: StorageUnit, tile: Tile, lane: Lane): Int {
        val fragments = ArrayList<Pair<ByteArray, CapsuleView>>()
        unit.scan(ChronoKeys.fragments(tile, lane)).use { c -> while (c.next()) fragments += c.key() to lane.open(c.value()) }
        val key = tile.packed to lane
        val seen = dirty[key]
        if (fragments.isEmpty()) {
            unit.afterCommit { dirty.remove(key, seen) }
            return 0
        }
        var newest: Pair<ByteArray, CapsuleView>? = null
        unit.scan(ChronoKeys.capsules(tile, lane)).use { c -> if (c.next()) newest = c.key() to lane.open(c.value()) }

        val byWindow = LinkedHashMap<Long, RowsBuilder>()
        for ((k, view) in fragments) {
            val rows = view.rows()
            val builder = byWindow.getOrPut(view.window) { RowsBuilder(maxOf(rows.size, 16)) }
            for (r in 0 until rows.size) builder.add(rows, r)
            unit.delete(k)
        }
        val tail = newest
        if (tail != null) {
            val (tailKey, tailView) = tail
            val joining = byWindow[tailView.window]
            if (joining != null && tailView.rowCount + joining.size <= settings.capsuleRows) {
                val rows = tailView.rows()
                for (r in 0 until rows.size) joining.add(rows, r)
                forget(unit, tile, lane, tailKey, tailView)
            }
        }
        var rows = 0
        for ((window, builder) in byWindow) {
            val merged = builder.rows().sorted()
            val bytes = lane.encode(merged, window)
            val view = lane.open(MemorySegment.ofArray(bytes))
            file(unit, tile, lane, view, bytes)
            rows += merged.size
            mergedBytes += bytes.size
        }
        merges++
        unit.afterCommit { dirty.remove(key, seen) }
        return rows
    }

    /** Puts a capsule and every row that finds it. */
    fun file(unit: StorageUnit, tile: Tile, lane: Lane, view: CapsuleView, bytes: ByteArray) {
        ChronoKeys.filing(tile, lane, view.firstSeq, view.lastSeq, view.millis()[0], TrackIds.of(interning, unit, lane, view), bytes, unit::put)
    }

    /** Deletes a capsule and every row that finds it. The key says which rows: a purge may have trimmed the header. */
    fun forget(unit: StorageUnit, tile: Tile, lane: Lane, key: ByteArray, view: CapsuleView) {
        val last = Tag.CHRONO_CAPSULE.long(key, 4)
        unit.delete(key)
        unit.delete(ChronoKeys.time(last, tile, lane))
        for (id in TrackIds.of(interning, unit, lane, view)) if (id != 0) unit.delete(ChronoKeys.track(id, last, tile, lane))
    }

}
