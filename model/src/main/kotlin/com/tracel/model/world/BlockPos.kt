package com.tracel.model.world


/** Block's coordinates. */
public data class BlockPos(
    public val world: WorldId,
    public val x: Int,
    public val y: Int,
    public val z: Int,
)

/** Tiles are squares of this many cells on a side, as a power of two. */
public const val TILE_SHIFT: Int = 4
