package com.tracel.engine.rollback.structure

import com.tracel.model.world.block.BlockShape
import com.tracel.model.world.entity.EntityShape

/** What the planner has to be told about the world it plans for. */
public interface WorldRules {
    /** Nothing at all stands in a cell shaped like [shape]. */
    public fun isEmpty(shape: BlockShape): Boolean

    /** [entity] is a block in motion: it exists only until it lands, and is never worth putting back. */
    public fun isMovingBlock(entity: EntityShape): Boolean

    /** [entity] hangs off a block, and cannot share its cell with one. */
    public fun isHanging(entity: EntityShape): Boolean

    /** [entity] comes loose when a block is put back where it hung. */
    public fun dropsWhenBlockReturns(entity: EntityShape): Boolean
}
