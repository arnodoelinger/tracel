package com.tracel.plugin.specifics.block

import com.tracel.model.world.block.BlockShape

/** The block a piston turns what it pushes into for the length of the push. */
internal const val MOVING_PISTON: String = "minecraft:moving_piston"

/** Whether this shape was caught mid-push by a piston. */
internal fun BlockShape.isMovingPiston(): Boolean = data.value.startsWith(MOVING_PISTON)
