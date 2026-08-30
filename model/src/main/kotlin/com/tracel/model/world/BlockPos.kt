package com.tracel.model.world

import com.tracel.model.id.WorldId

/** Block's coordinates. */
public data class BlockPos(
    public val world: WorldId,
    public val x: Int,
    public val y: Int,
    public val z: Int,
)
