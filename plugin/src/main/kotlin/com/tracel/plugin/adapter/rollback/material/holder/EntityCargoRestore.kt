package com.tracel.plugin.adapter.rollback.material.holder

import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.plugin.adapter.item.toItemKey
import com.tracel.plugin.adapter.rollback.material.census.census
import com.tracel.plugin.adapter.rollback.material.item.WornStacks
import com.tracel.plugin.adapter.world.worldOf
import com.tracel.plugin.rollback.material.ApplyResult
import com.tracel.plugin.rollback.material.ENTITY_GONE_AFTER_RESTORE
import com.tracel.plugin.rollback.material.ENTITY_GONE_AT_PLAN
import com.tracel.plugin.rollback.material.MaterialRestorer
import com.tracel.plugin.rollback.material.census.EntityCensus
import com.tracel.plugin.rollback.material.spill.Spill
import com.tracel.plugin.specifics.entity.MOUNT_CHEST
import com.tracel.plugin.util.geometry.regionKey
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import org.bukkit.inventory.ItemStack

internal val CHEST: ItemKey = ItemStack(MOUNT_CHEST).toItemKey()

private const val NO_HULL = "entity no longer exists"

private const val ELSEWHERE = "entity is in another region"

private const val GONE_SPILLED = "the entity is gone; what it was owed lies on the ground where it last stood"

internal val NO_HULL_FAILURE = ApplyResult.Failed(NO_HULL)

internal val ELSEWHERE_FAILURE = ApplyResult.Failed(ELSEWHERE)

/** One global lookup for entity cargo restore. */
internal suspend fun MaterialRestorer.restoreEntityCargo(
    work: Map<HolderId.Entity, Map<ItemKey, Long>>,
    forms: Map<ItemKey, ByteArray>,
    gone: Set<HolderId>,
    census: EntityCensus,
    sink: MutableCollection<Spill>,
    asOf: Long? = null,
    worn: WornStacks? = null,
): List<Pair<HolderId, ApplyResult>> {
    val out = ArrayList<Pair<HolderId, ApplyResult>>(work.size)
    val live = LinkedHashMap<HolderId.Entity, Map<ItemKey, Long>>()
    for ((holder, deltas) in work) {
        val taking = deltas.values.all { it < 0L }
        if (holder in gone && taking) out += holder to ENTITY_GONE_AT_PLAN
        else live[holder] = deltas
    }
    if (live.isEmpty()) return out

    data class Located(
        val holder: HolderId.Entity,
        val deltas: Map<ItemKey, Long>,
        val at: HolderId.Block,
        val needsTracker: Boolean,
    )

    val found = ArrayList<Located>(live.size)
    val missing = ArrayList<HolderId.Entity>()
    val unknown = LinkedHashMap<HolderId.Entity, Map<ItemKey, Long>>()
    for ((holder, deltas) in live) {
        val where = census.at[holder.uuid]
        when {
            where != null -> found += Located(holder, deltas, where, holder.uuid in census.trackerNeeded)
            holder.uuid in census.missing -> missing += holder
            else -> unknown[holder] = deltas
        }
    }
    // Census miss: one extra trip, not one per holder
    if (unknown.isNotEmpty()) {
        val late = withContext(services.schedulers.global) { census(HashSet<HolderId>(unknown.keys), unknown) }
        for ((holder, deltas) in unknown) {
            val where = late.at[holder.uuid]
            if (where == null) missing += holder
            else found += Located(holder, deltas, where, holder.uuid in late.trackerNeeded)
        }
    }
    val located = Pair(found, missing)
    for (holder in located.second) {
        // What a gone hull was owed goes on the ground where it last stood; giving it to nothing loses it
        val spilled = live[holder]?.let { spillForGoneHull(holder, it, forms, sink) } ?: false
        out += holder to if (spilled) ApplyResult.Failed(GONE_SPILLED) else ENTITY_GONE_AFTER_RESTORE
    }
    val waitFor = located.first.firstOrNull { it.needsTracker }
    if (waitFor != null) awaitTicks(2)

    val grouped = located.first.groupBy { it.at.regionKey() }
    val applied = coroutineScope {
        grouped.values.map { group ->
            async {
                val at = group.first().at
                worldOf(at.world)?.let { world ->
                    runCatching {
                        world.getChunkAtAsync(at.x shr 4, at.z shr 4).await()
                    }
                }
                withContext(services.schedulers.region(at)) {
                    val world = worldOf(at.world)
                    if (world != null) runCatching { world.getChunkAt(at.x shr 4, at.z shr 4).entities }
                    group.map { row ->
                        row.holder to inRegion {
                            val failed = fillEntityCargo(row.holder, row.deltas, forms, sink, asOf, worn)
                            if (failed === NO_HULL_FAILURE && world != null) spillGives(
                                row.holder,
                                row.deltas,
                                forms,
                                world,
                                row.at,
                                sink
                            )
                            failed
                        }
                    }
                }.map { (holder, result) ->
                    if (result !== ELSEWHERE_FAILURE) return@map holder to result
                    val row = group.first { it.holder == holder }
                    holder to withContext(services.schedulers.entity(holder.uuid)) {
                        inRegion { fillEntityCargo(row.holder, row.deltas, forms, sink, asOf, worn) }
                    }
                }
            }
        }.awaitAll().flatten()
    }
    out += applied
    return out
}
