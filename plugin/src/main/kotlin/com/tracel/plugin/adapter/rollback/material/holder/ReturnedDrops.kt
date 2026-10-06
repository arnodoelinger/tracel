package com.tracel.plugin.adapter.rollback.material.holder

import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.model.world.BlockPos
import com.tracel.plugin.adapter.rollback.material.item.stackFor
import com.tracel.plugin.adapter.rollback.material.item.stacksOf
import com.tracel.plugin.adapter.rollback.material.spill.recordSpilled
import com.tracel.plugin.adapter.world.worldOf
import com.tracel.plugin.listener.support.drop.dropTracked
import com.tracel.plugin.rollback.material.ApplyResult
import com.tracel.plugin.rollback.material.MaterialRestorer
import com.tracel.plugin.rollback.material.spill.Spill
import com.tracel.plugin.util.geometry.regionKey
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.bukkit.Location

/** Respawn a consumed drop. */
internal suspend fun MaterialRestorer.spawnReturnedDrops(
    work: Map<HolderId.ItemEntity, Map<ItemKey, Long>>,
    respawnAt: Map<HolderId.ItemEntity, HolderId>,
    forms: Map<ItemKey, ByteArray>,
    sink: MutableCollection<Spill>,
): List<Pair<HolderId, ApplyResult>> = coroutineScope {
    val out = ArrayList<Pair<HolderId, ApplyResult>>()

    // Resolve coords before grouping. Two throws share origin-of-nowhere (UNATTRIBUTED /
    // a UUID with no BlockPos). Group by that shared nothing and Folia sends both to one
    // region thread while they actually lay a thousand blocks apart — wrong region, missed
    // spawn, ledger holding a ghost pile. Each drop's last remembered cell is the group key.
    val placed = LinkedHashMap<HolderId.ItemEntity, HolderId.Block>()
    for (holder in work.keys) {
        // RAM for recent; disk for piles that died before restart
        val where = respawnAt[holder]?.toBlockHolder() ?: services.groundWhereabouts.at(holder.uuid)
        if (where == null) out += holder to ApplyResult.Failed("nowhere to put the drop back")
        else placed[holder] = where
    }
    if (placed.isEmpty()) return@coroutineScope out

    out += placed.entries.groupBy { it.value.regionKey() }.values.map { group ->
        async {
            withContext(services.schedulers.region(group.first().value)) {
                group.map { (holder, at) ->
                    holder to inRegion {
                        spawnReturnedDrop(
                            holder,
                            work.getValue(holder),
                            at,
                            forms,
                            sink
                        )?.let { ApplyResult.Failed(it) }
                    }
                }
            }
        }
    }.awaitAll().flatten()
    out
}

/** Spawns one drop pile at [at] for every positive key in [deltas], tracked in [sink] for later spill accounting. */
internal fun MaterialRestorer.spawnReturnedDrop(
    vanished: HolderId.ItemEntity,
    deltas: Map<ItemKey, Long>,
    at: HolderId.Block,
    forms: Map<ItemKey, ByteArray>,
    sink: MutableCollection<Spill>,
): String? {
    val world = worldOf(at.world) ?: return "world is not loaded"
    val loc = Location(world, at.x + 0.5, at.y + 0.5, at.z + 0.5)
    val where = BlockPos(at.world, at.x, at.y, at.z)
    val spilled = ArrayList<Spill>()
    try {
        for ((itemKey, qty) in deltas) {
            if (qty <= 0L) continue
            val template = stackFor(itemKey, 1, forms[itemKey]) ?: return "unknown material ${itemKey.material}"
            for (stack in stacksOf(itemKey, qty, template)) {
                spilled += Spill(vanished, services.dropTracked(stack, itemKey, world, loc), where)
            }
        }
    } finally {
        sink += spilled
        recordSpilled(vanished, where, spilled)
    }
    return null
}
