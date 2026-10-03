package com.tracel.plugin.integration.worldedit

import com.sk89q.worldedit.world.block.BlockState
import com.tracel.model.world.block.BlockDataKey
import com.tracel.model.world.block.BlockShape
import com.tracel.plugin.adapter.block.BlockDataCache
import java.util.concurrent.ConcurrentHashMap

private val shapes = ConcurrentHashMap<BlockState, BlockShape>()

/** The shape `WorldEdit`'s [state] has on the `Bukkit` side, spelled the way the rest of the log spells it. */
internal fun logShape(state: BlockState): BlockShape = shapes.getOrPut(state) {
    val raw = state.asString
    BlockShape(BlockDataKey(BlockDataCache.of(BlockDataKey(raw))?.asString ?: raw))
}
