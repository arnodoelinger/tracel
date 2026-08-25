package com.tracel.engine.log

import com.tracel.annotations.CauseKind
import com.tracel.model.holder.HolderId
import com.tracel.model.id.WorldId

/**
 * Lookup query over the [TransactionLog]: every non-empty field narrows the result,
 * an empty [LookupFilter] matches everything.
 */
public data class LookupFilter(
    public val holders: Set<HolderId> = emptySet(),
    public val excludedHolders: Set<HolderId> = emptySet(),
    public val material: String? = null,
    public val causes: Set<CauseKind> = emptySet(),
    public val since: Long? = null,
    public val until: Long? = null,
    public val region: LookupRegion? = null,
    public val limit: Int = 100,
    public val offset: Int = 0,
)

/** A rectangle of chunks in one world. */
public data class LookupRegion(
    public val world: WorldId,
    public val minChunkX: Int,
    public val maxChunkX: Int,
    public val minChunkZ: Int,
    public val maxChunkZ: Int,
) {
    /** How many chunk-column prefixes a scan of this region has to visit. */
    public val chunks: Int
        get() = (maxChunkX - minChunkX + 1).toLong().times(maxChunkZ - minChunkZ + 1)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
