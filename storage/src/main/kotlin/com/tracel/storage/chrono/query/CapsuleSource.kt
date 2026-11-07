package com.tracel.storage.chrono.query

import com.tracel.storage.chrono.Lane
import com.tracel.storage.chrono.Tile
import com.tracel.storage.chrono.capsule.CapsuleView
import java.util.TreeMap

/** Where capsules live, as a reader sees them: by tile, by actor, by recency, one lane at a time. */
interface CapsuleSource {
    /** Capsules of [tile] on [lane], newest first. */
    fun tile(tile: Tile, lane: Lane, visit: (CapsuleView) -> Boolean)

    /** Capsules on [lane] that [actor] has a change in, newest first, each once. */
    fun actor(actor: Int, lane: Lane, visit: (Tile, CapsuleView) -> Boolean)

    /** Every capsule on [lane], newest first. */
    fun recent(lane: Lane, visit: (Tile, CapsuleView) -> Boolean)

    companion object {
        val EMPTY: CapsuleSource = MemoryCapsules()
    }
}

/** A [CapsuleSource] on the heap. */
class MemoryCapsules : CapsuleSource {
    private class Entry(val tile: Tile, val capsule: CapsuleView)

    private val byTile = HashMap<Pair<Long, Lane>, TreeMap<Long, CapsuleView>>()
    private val byLast = HashMap<Lane, TreeMap<Long, Entry>>()

    fun add(tile: Tile, capsule: CapsuleView) {
        byTile.getOrPut(tile.packed to capsule.lane) { TreeMap() }[capsule.lastSeq] = capsule
        byLast.getOrPut(capsule.lane) { TreeMap() }[capsule.lastSeq] = Entry(tile, capsule)
    }

    val size: Int get() = byLast.values.sumOf { it.size }

    override fun tile(tile: Tile, lane: Lane, visit: (CapsuleView) -> Boolean) {
        val all = byTile[tile.packed to lane] ?: return
        for (capsule in all.descendingMap().values) if (!visit(capsule)) return
    }

    override fun actor(actor: Int, lane: Lane, visit: (Tile, CapsuleView) -> Boolean) {
        for (e in byLast[lane]?.descendingMap()?.values ?: return) {
            if (actor !in e.capsule.trackIds) continue
            if (!visit(e.tile, e.capsule)) return
        }
    }

    override fun recent(lane: Lane, visit: (Tile, CapsuleView) -> Boolean) {
        for (e in byLast[lane]?.descendingMap()?.values ?: return) if (!visit(e.tile, e.capsule)) return
    }
}
