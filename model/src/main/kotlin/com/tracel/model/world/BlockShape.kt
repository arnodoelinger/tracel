package com.tracel.model.world

/** What a block looks like. */
public data class BlockShape(
    public val data: BlockDataKey,
    public val extras: BlockExtras? = null,
) {
    public companion object {
        public val AIR: BlockShape = BlockShape(BlockDataKey("minecraft:air"))
    }
}
