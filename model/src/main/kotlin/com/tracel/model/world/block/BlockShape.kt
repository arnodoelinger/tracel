package com.tracel.model.world.block

import com.tracel.annotations.Unstable

/** What a block looks like. */
@Unstable
public data class BlockShape(
    public val data: BlockDataKey,
    public val extras: BlockExtras? = null,
) {
    /** Air of any kind. */
    public val isAirLike: Boolean
        get() = this == AIR || data.value == "minecraft:cave_air" || data.value == "minecraft:void_air"

    public companion object {
        /** Air. */
        public val AIR: BlockShape = BlockShape(BlockDataKey("minecraft:air"))
    }
}
