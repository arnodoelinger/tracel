package com.tracel.engine.log

import com.tracel.annotations.CauseKind
import com.tracel.engine.world.WorldLog
import com.tracel.model.holder.HolderId
import com.tracel.model.id.WorldId
import com.tracel.model.world.ActionKind
import com.tracel.model.world.BlockPos

/**
 * Lookup query over the [TransactionLog] and the
 * [WorldLog] alike: every non-empty field narrows the result,
 * an empty [LookupFilter] matches everything.
 */
public data class LookupFilter(
    public val holders: Set<HolderId> = emptySet(),
    public val excludedHolders: Set<HolderId> = emptySet(),
    public val material: String? = null,
    public val causes: Set<CauseKind> = emptySet(),
    public val excludedCauses: Set<CauseKind> = emptySet(),
    public val actions: Set<ActionKind> = emptySet(),
    public val since: Long? = null,
    public val until: Long? = null,
    public val region: LookupRegion? = null,
    public val world: WorldId? = null,
    public val limit: Int = 100,
    public val offset: Int = 0,
)

/** A rectangle of chunks in one world, optionally tightened to a block box. */
public data class LookupRegion(
    public val world: WorldId,
    public val minChunkX: Int,
    public val maxChunkX: Int,
    public val minChunkZ: Int,
    public val maxChunkZ: Int,
    public val minX: Int = Int.MIN_VALUE,
    public val maxX: Int = Int.MAX_VALUE,
    public val minY: Int = Int.MIN_VALUE,
    public val maxY: Int = Int.MAX_VALUE,
    public val minZ: Int = Int.MIN_VALUE,
    public val maxZ: Int = Int.MAX_VALUE,
) {
    /** Whether [pos] sits in this region, world included. */
    public fun contains(pos: BlockPos): Boolean =
        pos.world == world && containsBlock(pos.x, pos.y, pos.z)

    /** Whether these coordinates sit in this region, ignoring which world they belong to. */
    public fun containsBlock(x: Int, y: Int, z: Int): Boolean =
        (x shr 4) in minChunkX..maxChunkX &&
            (z shr 4) in minChunkZ..maxChunkZ &&
            x in minX..maxX && y in minY..maxY && z in minZ..maxZ
}
