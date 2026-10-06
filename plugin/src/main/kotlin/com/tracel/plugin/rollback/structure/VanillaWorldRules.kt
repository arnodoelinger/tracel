package com.tracel.plugin.rollback.structure

import com.tracel.engine.rollback.structure.WorldRules
import com.tracel.model.world.block.BlockShape
import com.tracel.model.world.entity.EntityShape
import com.tracel.plugin.util.isAirLike

/** What the structure planner has to know about the vanilla world. */
internal object VanillaWorldRules : WorldRules {
    private val HANGINGS = setOf("item_frame", "glow_item_frame", "painting", "leash_knot")

    override fun isEmpty(shape: BlockShape): Boolean = shape.isAirLike

    override fun isMovingBlock(entity: EntityShape): Boolean = entity.type.value.endsWith(":falling_block")

    override fun isHanging(entity: EntityShape): Boolean = entity.type.value.substringAfter(':') in HANGINGS

    override fun dropsWhenBlockReturns(entity: EntityShape): Boolean =
        entity.type.value.substringAfter(':') == "painting"
}
