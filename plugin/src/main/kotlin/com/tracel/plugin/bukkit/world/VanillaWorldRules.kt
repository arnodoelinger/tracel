package com.tracel.plugin.bukkit.world

import com.tracel.engine.rollback.structure.WorldRules
import com.tracel.model.world.block.BlockShape
import com.tracel.model.world.entity.EntityShape
import com.tracel.plugin.bukkit.block.isAirLike
import com.tracel.plugin.bukkit.entity.FALLING_BLOCK_SUFFIX
import com.tracel.plugin.bukkit.entity.HangingEntity

/** What the structure planner has to know about the vanilla world. */
internal object VanillaWorldRules : WorldRules {
    override fun isEmpty(shape: BlockShape): Boolean = shape.isAirLike

    override fun isMovingBlock(entity: EntityShape): Boolean = entity.type.value.endsWith(FALLING_BLOCK_SUFFIX)

    override fun isHanging(entity: EntityShape): Boolean = entity.type.value.substringAfter(':') in HangingEntity.ids

    override fun dropsWhenBlockReturns(entity: EntityShape): Boolean =
        entity.type.value.substringAfter(':') in HangingEntity.knockedOffIds
}
