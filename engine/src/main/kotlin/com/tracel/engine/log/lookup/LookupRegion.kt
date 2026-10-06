package com.tracel.engine.log.lookup

import com.tracel.model.id.WorldId
import com.tracel.model.world.BlockPos
import com.tracel.model.world.TILE_SHIFT

/**
 * An area of one [world]: a rectangle of tiles, which is what an index can look up cheaply, optionally tightened to an
 * exact box of cells by the [minX]..[maxZ] bounds.
 */
public data class LookupRegion(
    public val world: WorldId,
    public val minTileX: Int,
    public val maxTileX: Int,
    public val minTileZ: Int,
    public val maxTileZ: Int,
    public val minX: Int = Int.MIN_VALUE,
    public val maxX: Int = Int.MAX_VALUE,
    public val minY: Int = Int.MIN_VALUE,
    public val maxY: Int = Int.MAX_VALUE,
    public val minZ: Int = Int.MIN_VALUE,
    public val maxZ: Int = Int.MAX_VALUE,
) {
    /** Whether [pos] lies in this region, in its world. */
    public fun contains(pos: BlockPos): Boolean =
        pos.world == world && containsBlock(pos.x, pos.y, pos.z)

    /** Whether the cell at [x], [y], [z] lies in this region, whichever world it is taken to be in. */
    public fun containsBlock(x: Int, y: Int, z: Int): Boolean =
        (x shr TILE_SHIFT) in minTileX..maxTileX &&
                (z shr TILE_SHIFT) in minTileZ..maxTileZ &&
                x in minX..maxX && y in minY..maxY && z in minZ..maxZ
}
