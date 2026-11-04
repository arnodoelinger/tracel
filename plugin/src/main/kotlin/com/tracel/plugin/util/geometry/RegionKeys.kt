package com.tracel.plugin.util.geometry

import com.tracel.model.holder.HolderId
import com.tracel.model.world.BlockPos

/** Chunk this block sits in. Same key for every block in that chunk, whatever the height. */
internal fun BlockPos.regionKey(): Any = Triple(world, x shr 4, z shr 4)

/**
 * Chunk this holder sits in, when it has coordinates.
 *
 * A holder with no block is its own group — do not mix it with a chunk of something else.
 */
internal fun HolderId.regionKey(): Any = when (this) {
    is HolderId.Block -> Triple(world, x shr 4, z shr 4)
    is HolderId.PlacedBlock -> Triple(world, x shr 4, z shr 4)
    else -> this
}
