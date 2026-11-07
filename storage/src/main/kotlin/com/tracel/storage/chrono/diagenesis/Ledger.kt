package com.tracel.storage.chrono.diagenesis

import com.tracel.storage.chrono.Lane
import com.tracel.storage.chrono.Tile
import java.util.concurrent.ConcurrentHashMap

/**
 * What is waiting to be merged, and what the collector has been told about it.
 *
 * In memory only: a restart finds it again.
 */
internal class Ledger {
    class Entry(val since: Long, val fragments: Int, val bytes: Long, val urgent: Boolean, val holdUntil: Long)

    private val waiting = ConcurrentHashMap<Pair<Long, Lane>, Entry>()
    private val tails = ConcurrentHashMap<Pair<Long, Lane>, Long>()
    private val reads = ConcurrentHashMap<Long, Int>()
    private val pins = ConcurrentHashMap.newKeySet<Long>()

    fun note(tile: Tile, lane: Lane, bytes: Int, now: Long) {
        waiting.merge(tile.packed to lane, Entry(now, 1, bytes.toLong(), false, 0L)) { old, _ ->
            Entry(old.since, old.fragments + 1, old.bytes + bytes, old.urgent, old.holdUntil)
        }
    }

    fun urgent(tile: Tile) {
        for (lane in Lane.entries) waiting.computeIfPresent(tile.packed to lane) { _, e ->
            Entry(e.since, e.fragments, e.bytes, true, 0L)
        }
    }

    fun hold(tile: Tile, until: Long) {
        for (lane in Lane.entries) waiting.computeIfPresent(tile.packed to lane) { _, e ->
            Entry(e.since, e.fragments, e.bytes, false, until)
        }
    }

    fun entries(): Set<Map.Entry<Pair<Long, Lane>, Entry>> = waiting.entries

    fun get(key: Pair<Long, Lane>): Entry? = waiting[key]

    fun settle(key: Pair<Long, Lane>, seen: Entry?) {
        if (seen != null) waiting.remove(key, seen) else waiting.remove(key)
    }

    fun backlog(): Int = waiting.values.sumOf { it.fragments }

    fun tail(key: Pair<Long, Lane>): Long = tails[key] ?: 0L

    fun tail(key: Pair<Long, Lane>, bytes: Long) {
        tails[key] = bytes
    }

    fun read(tile: Tile) {
        reads.merge(tile.packed, 1, Int::plus)
    }

    fun reads(tile: Tile): Int = reads[tile.packed] ?: 0

    fun fade() {
        reads.replaceAll { _, n -> n shr 1 }
        reads.values.removeIf { it == 0 }
    }

    fun pin(tile: Tile, pinned: Boolean) {
        if (pinned) pins += tile.packed else pins -= tile.packed
    }

    fun pinned(tile: Tile): Boolean = tile.packed in pins
}
