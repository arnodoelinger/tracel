package com.tracel.plugin.adapter.rollback.structure.rescue

import com.tracel.model.holder.HolderId
import com.tracel.model.world.WorldId
import com.tracel.plugin.adapter.world.ownsChunkAt
import com.tracel.plugin.rollback.structure.StructureRestorer
import kotlinx.coroutines.withContext
import org.bukkit.World
import org.bukkit.util.BoundingBox
import kotlin.math.floor

/** Rescue chunks. */
internal suspend fun <T> StructureRestorer.inChunk(world: World, x: Int, y: Int, z: Int, work: () -> T): T =
    if (ownsChunkAt(world, x, z)) work()
    else withContext(services.schedulers.region(HolderId.Block(WorldId(world.uid), x, y, z))) { work() }

/** Chunk identifier. */
internal fun chunkOf(x: Int, z: Int): Long = (x shr 4).toLong() shl 32 or ((z shr 4).toLong() and 0xFFFFFFFFL)

/** Cells by chunk. */
internal fun cellsByChunk(box: BoundingBox): Collection<List<IntArray>> {
    val groups = HashMap<Long, MutableList<IntArray>>()
    for (x in floor(box.minX).toInt()..floor(box.maxX - 1e-4).toInt())
        for (y in floor(box.minY).toInt()..floor(box.maxY - 1e-4).toInt())
            for (z in floor(box.minZ).toInt()..floor(box.maxZ - 1e-4).toInt())
                groups.getOrPut(chunkOf(x, z)) { ArrayList() } += intArrayOf(x, y, z)
    return groups.values
}
