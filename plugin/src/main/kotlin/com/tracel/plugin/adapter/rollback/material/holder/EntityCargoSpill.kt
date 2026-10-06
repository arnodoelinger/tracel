package com.tracel.plugin.adapter.rollback.material.holder

import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.plugin.adapter.rollback.material.item.Moves
import com.tracel.plugin.adapter.rollback.material.item.stackFor
import com.tracel.plugin.adapter.rollback.material.item.stacksOf
import com.tracel.plugin.adapter.rollback.material.spill.spillInRegion
import com.tracel.plugin.adapter.world.worldOf
import com.tracel.plugin.rollback.material.MaterialRestorer
import com.tracel.plugin.rollback.material.spill.Spill
import kotlinx.coroutines.withContext
import org.bukkit.Location
import org.bukkit.World

internal fun MaterialRestorer.spillGives(
    holder: HolderId.Entity,
    deltas: Map<ItemKey, Long>,
    forms: Map<ItemKey, ByteArray>,
    world: World,
    at: HolderId.Block,
    sink: MutableCollection<Spill>,
) {
    val moves = Moves()
    for ((itemKey, delta) in deltas) {
        if (delta <= 0L) continue
        val template = stackFor(itemKey, 1, forms[itemKey]) ?: continue
        for (stack in stacksOf(itemKey, delta, template)) moves.overflow += itemKey to stack
    }
    if (moves.overflow.isNotEmpty()) spillInRegion(
        holder,
        moves,
        world,
        Location(world, at.x + 0.5, at.y + 1.0, at.z + 0.5),
        sink
    )
}

internal suspend fun MaterialRestorer.spillForGoneHull(
    holder: HolderId.Entity,
    deltas: Map<ItemKey, Long>,
    forms: Map<ItemKey, ByteArray>,
    sink: MutableCollection<Spill>,
): Boolean {
    if (deltas.values.any { it <= 0L }) return false
    val here = services.whereabouts.lastKnown(holder.uuid) ?: return false
    val world = worldOf(here.world) ?: return false
    val at = HolderId.Block(here.world, here.x, here.y, here.z)
    withContext(services.schedulers.region(at)) { spillGives(holder, deltas, forms, world, at, sink) }
    return true
}
