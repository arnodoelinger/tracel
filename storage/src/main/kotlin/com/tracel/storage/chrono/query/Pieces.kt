package com.tracel.storage.chrono.query

import com.tracel.storage.chrono.Lane
import com.tracel.storage.chrono.Rows
import com.tracel.storage.chrono.Tile
import com.tracel.storage.chrono.capsule.Capsule
import com.tracel.storage.chrono.capsule.CapsuleView

/** One capsule of a tile's history as a walk sees it. */
internal class Piece(val capsule: CapsuleView) {
    private var decoded: Rows? = null

    val firstSeq: Long get() = capsule.firstSeq
    val lastSeq: Long get() = capsule.lastSeq
    val minMillis: Long get() = capsule.minMillis
    val maxMillis: Long get() = capsule.maxMillis
    val blocks: Capsule? get() = capsule as? Capsule

    /**
     * Every row, in ascending sequence.
     *
     * Decoded once.
     */
    fun rows(): Rows = decoded ?: capsule.rows().also { decoded = it }
}

/** Gathers pieces from the source, in the order and up to the age a question needs. */
internal class Gatherer(private val source: CapsuleSource, private val slackMillis: Long) {
    /**
     * Every piece of [tile] on [lane] with a change at or after [since], oldest first.
     *
     * Capsules come newest first and the walk stops at the first one wholly older than [since],
     * give or take [slackMillis] for clocks that wobble.
     */
    fun tile(tile: Tile, lane: Lane, since: Long): List<Piece> {
        val out = ArrayList<Piece>()
        val floor = floorOf(since)
        source.tile(tile, lane) { capsule ->
            if (capsule.maxMillis < floor) return@tile false
            out += Piece(capsule)
            true
        }
        out.sortBy { it.firstSeq }
        return out
    }

    /**
     * Pieces anywhere on [lane] that [actor] has a change in, or every piece with [actor] `null`,
     * newest first.
     */
    fun walk(actor: Int?, lane: Lane, since: Long): List<Pair<Tile, Piece>> {
        val out = ArrayList<Pair<Tile, Piece>>()
        val floor = floorOf(since)
        val visit = { tile: Tile, capsule: CapsuleView ->
            if (capsule.maxMillis < floor) {
                false
            } else {
                out += tile to Piece(capsule)
                true
            }
        }
        if (actor == null) source.recent(lane, visit) else source.actor(actor, lane, visit)
        return out
    }

    private fun floorOf(since: Long): Long = if (since == Long.MIN_VALUE) Long.MIN_VALUE else since - slackMillis
}
