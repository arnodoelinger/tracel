package com.tracel.engine.world

import com.tracel.model.world.BlockPos

/**
 * A small number a block keeps about whatever just left it,
 * so a restore can put it back the way it lay.
 */
public interface BlockNotes {
    /** Every note on disk. Read once at start, before anything writes. */
    public fun loadAll(): Map<BlockPos, Int>

    /** Writes every note in [notes] as one commit. A `null` note is forgotten. */
    public suspend fun writeAll(notes: Map<BlockPos, Int?>)
}
