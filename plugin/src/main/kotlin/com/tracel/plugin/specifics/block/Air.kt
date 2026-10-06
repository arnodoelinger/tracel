package com.tracel.plugin.specifics.block

import com.tracel.model.world.block.BlockDataKey
import com.tracel.model.world.block.BlockShape

/** Every kind of air, by its block state. */
internal enum class AirBlock(val id: String) {
    AIR("minecraft:air"),
    CAVE_AIR("minecraft:cave_air"),
    VOID_AIR("minecraft:void_air"),
}

/** Air. */
internal val AIR: BlockShape = BlockShape(BlockDataKey(AirBlock.AIR.id))

/** Air of any kind. */
internal val BlockShape.isAirLike: Boolean
    get() = this == AIR || data.value == AirBlock.CAVE_AIR.id || data.value == AirBlock.VOID_AIR.id
