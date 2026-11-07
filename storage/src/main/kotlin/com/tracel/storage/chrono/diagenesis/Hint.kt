package com.tracel.storage.chrono.diagenesis

import com.tracel.storage.chrono.Tile

/**
 * What the game side knows that bytes do not say.
 *
 * Advice only: the collector may ignore it, and no answer ever depends on it.
 */
sealed interface Hint {
    /** Nothing will change in [tile] for a while: file it. */
    data class Quiet(val tile: Tile) : Hint

    /** Writes are pouring into [tiles]: hold merging there until they stop. */
    data class Burst(val tiles: Collection<Tile>) : Hint

    /** [tile] is read all the time: keep it out of the glacier. */
    data class Pin(val tile: Tile, val pinned: Boolean = true) : Hint

    /** World [world] is done for now: the next sweep seals and freezes everything in it, once. */
    data class Retire(val world: Int) : Hint
}
