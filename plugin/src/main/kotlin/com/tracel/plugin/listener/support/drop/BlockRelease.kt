package com.tracel.plugin.listener.support.drop

import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import kotlinx.coroutines.*
import org.bukkit.World
import org.bukkit.block.Block

/**
 * Block release.
 *
 * One emptying, located in the world — death drops use the same path and are not blocks.
 */
data class BlockRelease(
    val holder: HolderId,
    val world: World,
    val x: Int,
    val y: Int,
    val z: Int,
    val contents: Map<ItemKey, Long>? = null,
) {
    constructor(holder: HolderId, block: Block) : this(holder, block.world, block.x, block.y, block.z)
}
