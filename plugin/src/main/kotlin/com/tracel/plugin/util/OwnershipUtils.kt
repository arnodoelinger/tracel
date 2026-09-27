package com.tracel.plugin.util

import com.tracel.model.world.BlockPos
import org.bukkit.Bukkit
import org.bukkit.World

/** @return `true` if this thread owns the chunk at these block coordinates. */
internal fun ownsChunkAt(world: World, blockX: Int, blockZ: Int): Boolean =
    runCatching { Bukkit.isOwnedByCurrentRegion(world, blockX shr 4, blockZ shr 4) }.getOrDefault(false)

/**
 * @return `true` if this thread owns the chunk [at] is in.
 *
 * Missing world is not owned.
 */
internal fun ownsChunkAt(at: BlockPos): Boolean {
    val world = Bukkit.getWorld(at.world.uuid) ?: return false
    return ownsChunkAt(world, at.x, at.z)
}
