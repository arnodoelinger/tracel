package com.tracel.plugin.adapter.rollback.structure.rescue

import com.tracel.plugin.rollback.structure.StructureRestorer
import com.tracel.plugin.specifics.block.HazardBlock
import org.bukkit.*
import org.bukkit.block.Block

/** Rescue spots. */
internal suspend fun StructureRestorer.safeSpotNear(world: World, from: Location, up: Int): Location? =
    spotBetween(world, from, 0, up) ?: spotBetween(world, from, -SEARCH_DOWN, -1)

/** Rescue spot near the given location. */
internal suspend fun StructureRestorer.surfaceSpotNear(world: World, from: Location): Location? {
    val cx = from.blockX
    val cz = from.blockZ
    var best: Location? = null
    var bestScore = Double.MAX_VALUE
    for (chunkX in ((cx - SURFACE_RADIUS) shr 4)..((cx + SURFACE_RADIUS) shr 4))
        for (chunkZ in ((cz - SURFACE_RADIUS) shr 4)..((cz + SURFACE_RADIUS) shr 4)) {
            val (spot, score) = inChunk(world, chunkX shl 4, from.blockY, chunkZ shl 4) {
                if (!world.isChunkLoaded(chunkX, chunkZ)) return@inChunk null to Double.MAX_VALUE
                var near: Location? = null
                var nearScore = Double.MAX_VALUE
                for (x in maxOf(chunkX shl 4, cx - SURFACE_RADIUS)..minOf((chunkX shl 4) + 15, cx + SURFACE_RADIUS))
                    for (z in maxOf(chunkZ shl 4, cz - SURFACE_RADIUS)..minOf(
                        (chunkZ shl 4) + 15,
                        cz + SURFACE_RADIUS
                    )) {
                        val score = ((x - cx) * (x - cx) + (z - cz) * (z - cz)).toDouble()
                        if (score >= nearScore) continue
                        val y = world.getHighestBlockYAt(x, z) + 1
                        if (y + 1 >= world.maxHeight || !standable(world, x, y, z)) continue
                        nearScore = score
                        near = Location(world, x + 0.5, y.toDouble(), z + 0.5, from.yaw, from.pitch)
                    }
                near to nearScore
            }
            if (spot != null && score < bestScore) {
                best = spot
                bestScore = score
            }
        }
    return best
}

private suspend fun StructureRestorer.spotBetween(world: World, from: Location, lowest: Int, highest: Int): Location? {
    val cx = from.blockX
    val cy = from.blockY
    val cz = from.blockZ
    var best: Location? = null
    var bestScore = Double.MAX_VALUE
    for (chunkX in ((cx - SEARCH_RADIUS) shr 4)..((cx + SEARCH_RADIUS) shr 4))
        for (chunkZ in ((cz - SEARCH_RADIUS) shr 4)..((cz + SEARCH_RADIUS) shr 4)) {
            val (spot, score) = inChunk(world, chunkX shl 4, cy, chunkZ shl 4) {
                if (!world.isChunkLoaded(chunkX, chunkZ)) null to Double.MAX_VALUE
                else searchChunk(world, from, chunkX, chunkZ, lowest, highest)
            }
            if (spot != null && score < bestScore) {
                best = spot
                bestScore = score
            }
        }
    return best
}

private fun searchChunk(
    world: World,
    from: Location,
    chunkX: Int,
    chunkZ: Int,
    lowest: Int,
    highest: Int,
): Pair<Location?, Double> {
    val cx = from.blockX
    val cy = from.blockY
    val cz = from.blockZ
    var best: Location? = null
    var bestScore = Double.MAX_VALUE
    for (x in maxOf(chunkX shl 4, cx - SEARCH_RADIUS)..minOf((chunkX shl 4) + 15, cx + SEARCH_RADIUS))
        for (z in maxOf(chunkZ shl 4, cz - SEARCH_RADIUS)..minOf((chunkZ shl 4) + 15, cz + SEARCH_RADIUS)) {
            val dx = x - cx
            val dz = z - cz
            for (dy in lowest..highest) {
                val y = cy + dy
                if (y <= world.minHeight || y + 1 >= world.maxHeight) continue
                val score = (dx * dx + dz * dz + dy * dy * if (dy < 0) 6 else 2).toDouble()
                if (score >= bestScore || !standable(world, x, y, z)) continue
                bestScore = score
                best = Location(world, x + 0.5, y.toDouble(), z + 0.5, from.yaw, from.pitch)
            }
        }
    return best to bestScore
}

private fun standable(world: World, x: Int, y: Int, z: Int): Boolean {
    val feet = world.getBlockAt(x, y, z)
    val head = world.getBlockAt(x, y + 1, z)
    val floor = world.getBlockAt(x, y - 1, z)
    if (!clear(feet) || !clear(head)) return false
    if (floor.type in HazardBlock.materials || floor.isLiquid || floor.isPassable) return false
    return floor.collisionShape.boundingBoxes.any { it.maxY >= 1.0 - 1e-4 }
}

private fun clear(block: Block): Boolean =
    block.isPassable && !block.isLiquid && block.type !in HazardBlock.materials
