package com.tracel.plugin.rollback.structure

import com.tracel.model.holder.HolderId
import com.tracel.model.world.BlockPos
import com.tracel.plugin.adapter.world.worldOf
import com.tracel.plugin.util.regionKey
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.AnaloguePowerable
import org.bukkit.block.data.Lightable
import org.bukkit.block.data.Powerable

/** Update redstone. */
internal suspend fun StructureRestorer.wakeRedstoneAt(positions: Sequence<BlockPos>) {
    val distinct = positions.distinct().toList()
    if (distinct.isEmpty()) return
    coroutineScope {
        distinct.groupBy { it.regionKey() }.values.map { group ->
            async {
                val anchor = group.first()
                withContext(services.schedulers.region(HolderId.Block(anchor.world, anchor.x, anchor.y, anchor.z))) {
                    val world = worldOf(anchor.world) ?: return@withContext
                    val done = HashSet<Long>(group.size * 4)
                    for (pos in group) {
                        wake(world, pos, done)
                        for (face in NEIGHBOR_FACES) wake(world, pos.relative(face), done)
                    }
                }
            }
        }.awaitAll()
    }
}

private val NEIGHBOR_FACES = arrayOf(
    BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST,
)

private fun BlockPos.relative(face: BlockFace): BlockPos =
    BlockPos(world, x + face.modX, y + face.modY, z + face.modZ)

private fun BlockPos.packed(): Long =
    (x.toLong() and 0x3FFFFFF) or ((z.toLong() and 0x3FFFFFF) shl 26) or (y.toLong() shl 52)

private fun wake(world: World, pos: BlockPos, done: MutableSet<Long>) {
    if (!done.add(pos.packed())) return
    val block = world.getBlockAt(pos.x, pos.y, pos.z)
    wakeIfRedstone(block)
}

private fun wakeIfRedstone(block: Block) {
    when (val data = block.blockData) {
        is Powerable -> {
            val target = data.isPowered
            data.isPowered = !target
            block.setBlockData(data, true)
            data.isPowered = target
            block.setBlockData(data, true)
        }
        is AnaloguePowerable -> {
            val target = data.power
            data.power = if (target == 0) 1 else 0
            block.setBlockData(data, true)
            data.power = target
            block.setBlockData(data, true)
        }
        is Lightable -> {
            val target = data.isLit
            data.isLit = !target
            block.setBlockData(data, true)
            data.isLit = target
            block.setBlockData(data, true)
        }
        else -> Unit
    }
}
