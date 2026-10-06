package com.tracel.plugin.util

import com.tracel.model.world.block.BlockDataKey
import com.tracel.model.world.block.BlockShape

/** Air. */
internal val AIR: BlockShape = BlockShape(BlockDataKey("minecraft:air"))

/** Air of any kind. */
internal val BlockShape.isAirLike: Boolean
    get() = this == AIR || data.value == "minecraft:cave_air" || data.value == "minecraft:void_air"
