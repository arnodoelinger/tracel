package com.tracel.model.world.block

/** What a block looks like. */
public data class BlockShape(
    public val data: BlockDataKey,
    public val extras: BlockExtras? = null,
)
