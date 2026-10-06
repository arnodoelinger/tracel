package com.tracel.plugin.adapter.rollback.material.holder

import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.plugin.adapter.entity.remember
import com.tracel.plugin.adapter.rollback.material.census.census
import com.tracel.plugin.adapter.rollback.material.census.confirmGone
import com.tracel.plugin.adapter.rollback.material.item.WornStacks
import com.tracel.plugin.rollback.material.ApplyResult
import com.tracel.plugin.rollback.material.ENTITY_GONE_AFTER_RESTORE
import com.tracel.plugin.rollback.material.ENTITY_GONE_AT_PLAN
import com.tracel.plugin.rollback.material.MaterialRestorer
import com.tracel.plugin.rollback.material.census.EntityCensus
import com.tracel.plugin.rollback.material.spill.Spill
import com.tracel.plugin.util.geometry.regionKey
import com.tracel.plugin.util.holder.blockPos
import kotlinx.coroutines.*
import org.bukkit.Bukkit
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
    if (missing.isNotEmpty()) {
        val stillGone = confirmGone(missing.toSet())
        for (holder in missing.filter { it !in stillGone }) {
            val hint = services.groundWhereabouts.at(holder.uuid) ?: continue
            found += Located(holder, live.getValue(holder), hint)
        }
        missing.retainAll(stillGone)
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
internal suspend inline fun MaterialRestorer.inRegion(work: suspend () -> ApplyResult.Failed?): ApplyResult = try {
    work() ?: ApplyResult.Ok
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
): ApplyResult.Failed? {
    val item = Bukkit.getEntity(holder.uuid) as? Item ?: return ApplyResult.Failed("ground item no longer exists")
    val entry = deltas.entries.singleOrNull()
        ?: return ApplyResult.Failed("a ground item can only ever be a single-item-key Take source, got ${deltas.keys}")
    val (itemKey, delta) = entry
    if (delta >= 0) return ApplyResult.Failed("a ground item can only ever lose material during a rollback, got a gain")

    // Unknown material is not "out of reach"; don't let valueOf throw into the drift catch
    val material = runCatching { Material.valueOf(itemKey.material) }.getOrNull()
        ?: return ApplyResult.Failed("unknown material ${itemKey.material}")
    if (material != item.itemStack.type) return ApplyResult.Failed("ground item is no longer ${itemKey.material}")
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
    return if (remaining == 0L) null else ApplyResult.Failed(
        "${itemKey.material} x${-remaining} was no longer on the ground",
        mapOf(itemKey to -remaining),
    )
}

internal fun HolderId.toBlockHolder(): HolderId.Block? = when (this) {
    is HolderId.Block -> this
    is HolderId.PlacedBlock -> HolderId.Block(world, x, y, z)
    else -> blockPos()?.let { HolderId.Block(it.world, it.x, it.y, it.z) }
}
