package com.tracel.engine.world.edit

import com.tracel.model.world.BlockPos
import com.tracel.model.world.block.BlockShape

/** One cell going from the shape it had to the shape it has, [at] its place in the world. */
public data class BlockEdit(
    public val at: BlockPos,
    public val before: BlockShape,
    public val after: BlockShape,
)
