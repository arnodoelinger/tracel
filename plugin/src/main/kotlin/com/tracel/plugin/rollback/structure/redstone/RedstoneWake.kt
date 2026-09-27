package com.tracel.plugin.rollback.structure.redstone

import com.tracel.annotations.Unstable
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.holder.HolderId
import com.tracel.plugin.adapter.block.BlockDataCache
import com.tracel.model.world.BlockPos
import com.tracel.plugin.adapter.world.worldOf
import com.tracel.plugin.rollback.structure.StructureRestorer
import com.tracel.plugin.util.regionKey
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.data.AnaloguePowerable
import org.bukkit.block.data.Lightable
import org.bukkit.block.data.Powerable
import org.bukkit.block.data.type.Lectern
import org.bukkit.block.data.type.Observer
import org.bukkit.block.data.type.Switch
import org.bukkit.block.data.type.Tripwire
import org.bukkit.block.data.type.TripwireHook

/** Update redstone around what the restore wrote with physics off. */
@Unstable
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
                    for (pos in group) wake(world, pos, done)
                }
            }
        }.awaitAll()
    }
}

internal fun Iterable<StructureStep>.redstoneCells(): Sequence<BlockPos> = asSequence()
    .filterIsInstance<StructureStep.SetBlock>()
    .filter { step ->
        when (BlockDataCache.of(step.target.data)) {
            is Powerable, is AnaloguePowerable, is Lightable -> true
            else -> false
        }
    }
    .map { it.at }

private fun BlockPos.packed(): Long =
    (x.toLong() and 0x3FFFFFF) or ((z.toLong() and 0x3FFFFFF) shl 26) or (y.toLong() shl 52)

private fun wake(world: World, pos: BlockPos, done: MutableSet<Long>) {
    if (!done.add(pos.packed())) return
    val block = world.getBlockAt(pos.x, pos.y, pos.z)
    wakeIfRedstone(block)
}

private fun wakeIfRedstone(block: Block) {
    val data = block.blockData
    if (data is Switch || data is Observer || data is Lectern || data is Tripwire || data is TripwireHook) return
    if (block.type.name.endsWith("_PRESSURE_PLATE")) return
    when (data) {
        is Powerable -> {
            val target = data.isPowered
            data.isPowered = !target
            block.setBlockData(data, false)
            data.isPowered = target
            block.setBlockData(data, true)
        }
        is AnaloguePowerable -> {
            val target = data.power
            data.power = if (target == 0) 1 else 0
            block.setBlockData(data, false)
            data.power = target
            block.setBlockData(data, true)
        }
        is Lightable -> {
            val target = data.isLit
            data.isLit = !target
            block.setBlockData(data, false)
            data.isLit = target
            block.setBlockData(data, true)
        }
        else -> Unit
    }
}
