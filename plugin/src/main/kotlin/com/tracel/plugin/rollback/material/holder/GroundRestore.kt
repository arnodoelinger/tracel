package com.tracel.plugin.rollback.material.holder

import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.model.world.BlockPos
import com.tracel.plugin.adapter.world.worldOf
import com.tracel.plugin.listener.support.dropTracked
import com.tracel.plugin.rollback.material.ApplyResult
import com.tracel.plugin.rollback.material.ENTITY_GONE_AFTER_RESTORE
import com.tracel.plugin.rollback.material.ENTITY_GONE_AT_PLAN
import com.tracel.plugin.rollback.material.MaterialRestorer
import com.tracel.plugin.rollback.material.census.EntityCensus
import com.tracel.plugin.rollback.material.census.census
import com.tracel.plugin.rollback.material.item.WornStacks
import com.tracel.plugin.rollback.material.item.stackFor
import com.tracel.plugin.rollback.material.item.stacksOf
import com.tracel.plugin.rollback.material.spill.Spill
import com.tracel.plugin.util.blockPos
import com.tracel.plugin.util.regionKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Item

/** Restore ground items. */
internal suspend fun MaterialRestorer.restoreGroundItems(
    work: Map<HolderId.ItemEntity, Map<ItemKey, Long>>,
    gone: Set<HolderId>,
    census: EntityCensus,
    forms: Map<ItemKey, ByteArray> = emptyMap(),
    sink: MutableCollection<Spill>? = null,
    respawnAt: Map<HolderId.ItemEntity, HolderId> = emptyMap(),
    worn: WornStacks? = null,
): List<Pair<HolderId, ApplyResult>> {
    val out = ArrayList<Pair<HolderId, ApplyResult>>(work.size)
    val live = LinkedHashMap<HolderId.ItemEntity, Map<ItemKey, Long>>()
    val respawn = LinkedHashMap<HolderId.ItemEntity, Map<ItemKey, Long>>()
    for ((holder, deltas) in work) {
        val taking = deltas.values.all { it < 0L }
        val giving = deltas.values.all { it > 0L }
        when (holder) {
            in gone if taking -> out += holder to ENTITY_GONE_AT_PLAN
            in gone if giving -> respawn[holder] = deltas
            else -> live[holder] = deltas
        }
    }
    if (live.isEmpty() && respawn.isEmpty()) return out

    data class Located(val holder: HolderId.ItemEntity, val deltas: Map<ItemKey, Long>, val at: HolderId.Block)

    val found = ArrayList<Located>(live.size)
    val missing = ArrayList<HolderId.ItemEntity>()
    val unknown = LinkedHashMap<HolderId.ItemEntity, Map<ItemKey, Long>>()
    for ((holder, deltas) in live) {
        val where = census.at[holder.uuid]
        when {
            where != null -> found += Located(holder, deltas, where)
            holder.uuid in census.missing -> missing += holder
            else -> unknown[holder] = deltas
        }
    }
    if (unknown.isNotEmpty()) {
        val late = withContext(services.schedulers.global) { census(HashSet<HolderId>(unknown.keys), unknown) }
        for ((holder, deltas) in unknown) {
            val where = late.at[holder.uuid]
            if (where == null) missing += holder else found += Located(holder, deltas, where)
        }
    }
    val located = Pair(found, missing)
    for (holder in located.second) {
        val deltas = live[holder]
        if (deltas != null && deltas.values.all { it > 0L }) respawn[holder] = deltas
        else out += holder to ENTITY_GONE_AFTER_RESTORE
    }
    if (respawn.isNotEmpty() && sink != null) {
        out += spawnReturnedDrops(respawn, respawnAt, forms, sink)
    } else {
        for (holder in respawn.keys) out += holder to ENTITY_GONE_AFTER_RESTORE
    }

    val grouped = located.first.groupBy { it.at.regionKey() }
    val applied = coroutineScope {
        grouped.values.map { group ->
            async {
                val at = group.first().at
                withContext(services.schedulers.region(at)) {
                    group.map { row ->
                        row.holder to inRegion { takeGroundItem(row.holder, row.deltas, worn) }
                    }
                }
            }
        }.awaitAll().flatten()
    }
    out += applied
    return out
}

/** Whether it's in the region. */
internal suspend inline fun MaterialRestorer.inRegion(work: suspend () -> String?): ApplyResult = try {
    work()?.let(ApplyResult::Failed) ?: ApplyResult.Ok
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (moved: Throwable) {
    ApplyResult.Failed(moved.message ?: moved::class.java.simpleName)
}

/** Shrinks or removes a live ground item pile by [deltas]. A ground item can only ever be a single-key take. */
internal fun MaterialRestorer.takeGroundItem(
    holder: HolderId.ItemEntity,
    deltas: Map<ItemKey, Long>,
    worn: WornStacks? = null,
): String? {
    val item = Bukkit.getEntity(holder.uuid) as? Item ?: return "ground item no longer exists"
    val entry = deltas.entries.singleOrNull()
        ?: return "a ground item can only ever be a single-item-key Take source, got ${deltas.keys}"
    val (itemKey, delta) = entry
    if (delta >= 0) return "a ground item can only ever lose material during a rollback, got a gain"

    // Unknown material is not "out of reach"; don't let valueOf throw into the drift catch
    val material = runCatching { Material.valueOf(itemKey.material) }.getOrNull()
        ?: return "unknown material ${itemKey.material}"
    if (material != item.itemStack.type) return "ground item is no longer ${itemKey.material}"
    if (worn != null && WornStacks.wears(itemKey)) {
        worn.took(itemKey, item.itemStack.clone().apply { amount = minOf(amount.toLong(), -delta).toInt() })
    }

    val remaining = item.itemStack.amount + delta
    if (remaining > 0) {
        item.itemStack = item.itemStack.apply { amount = remaining.toInt() }
        return null
    }

    // Remember coords: undo must respawn this pile and the log never had a position
    services.groundWhereabouts.remember(item)
    services.selfManagedSpawns.forget(holder.uuid)
    services.selfManagedWorld.whileRestoring { item.remove() }

    // Short pile: pickup or merge. Silent success left the difference in two places
    return if (remaining == 0L) null else "${itemKey.material} x${-remaining} was no longer on the ground"
}

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
                    holder to inRegion { spawnReturnedDrop(holder, work.getValue(holder), at, forms, sink) }
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
    for ((itemKey, qty) in deltas) {
        if (qty <= 0L) continue
        val template = stackFor(itemKey, 1, forms[itemKey]) ?: return "unknown material ${itemKey.material}"
        for (stack in stacksOf(itemKey, qty, template)) {
            sink += Spill(vanished, services.dropTracked(stack, itemKey, world, loc), where)
        }
    }
    return null
}

private fun HolderId.toBlockHolder(): HolderId.Block? = when (this) {
    is HolderId.Block -> this
    is HolderId.PlacedBlock -> HolderId.Block(world, x, y, z)
    else -> blockPos()?.let { HolderId.Block(it.world, it.x, it.y, it.z) }
}
