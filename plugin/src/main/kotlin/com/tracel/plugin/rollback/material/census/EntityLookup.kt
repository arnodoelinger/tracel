package com.tracel.plugin.rollback.material.census

import com.tracel.model.holder.HolderId
import com.tracel.model.id.WorldId
import com.tracel.model.item.ItemKey
import com.tracel.plugin.rollback.material.MaterialRestorer
import com.tracel.plugin.rollback.trace.RollbackTrace
import com.tracel.plugin.util.namedByEntity
import java.util.UUID
import kotlin.time.TimeSource
import kotlinx.coroutines.withContext
import org.bukkit.Bukkit
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Item
import org.bukkit.entity.ItemFrame

/** Which UUID holders are gone. */
internal suspend fun MaterialRestorer.findVanished(
    holders: Set<HolderId>,
    trace: RollbackTrace, // TODO: remove me
): Set<HolderId> {
    val byUuid = holders.filterTo(HashSet()) { it.namedByEntity() }
    if (byUuid.isEmpty()) return emptySet()
    val dispatched = trace.stopwatch("global hop ms")
    val hop = TimeSource.Monotonic.markNow()
    return withContext(services.schedulers.global) {
        dispatched()
        trace.addNanos("find vanished items / hop", hop.elapsedNow().inWholeNanoseconds)
        val scan = TimeSource.Monotonic.markNow()
        val gone = byUuid.filterTo(HashSet()) { holder ->
            when (holder) {
                is HolderId.ItemEntity -> Bukkit.getEntity(holder.uuid) !is Item
                is HolderId.Entity -> false
                is HolderId.PlacedEntity -> false
                else -> false
            }
        }
        trace.addNanos("find vanished items / lookup", scan.elapsedNow().inWholeNanoseconds)
        gone
    }
}

/**
 * One global census of UUID destinations in [deltas].
 *
 * Does not depend on the ledger.
 */
internal suspend fun MaterialRestorer.locateDestinations(
    deltas: Map<HolderId, Map<ItemKey, Long>>,
    respawning: Set<UUID>,
): EntityCensus {
    val wanted: Set<HolderId> =
        deltas.keys.filterTo(HashSet()) { it is HolderId.ItemEntity || it is HolderId.Entity }
    if (wanted.isEmpty()) return EntityCensus.EMPTY
    return withContext(services.schedulers.global) { census(wanted, deltas, respawning) }
}

/**
 * Locates [wanted] UUID holders and flags which frames / stands need a tracker.
 *
 * Must already be on the global region.
 */
internal fun MaterialRestorer.census(
    wanted: Set<HolderId>,
    deltas: Map<out HolderId, Map<ItemKey, Long>>,
    respawning: Set<UUID> = emptySet(),
): EntityCensus {
    val at = HashMap<UUID, HolderId.Block>(wanted.size)
    val missing = HashSet<UUID>()
    val trackerNeeded = HashSet<UUID>()
    for (holder in wanted) {
        val uuid = when (holder) {
            is HolderId.ItemEntity -> holder.uuid
            is HolderId.Entity -> holder.uuid
            else -> continue
        }
        // Spawning now: unanswered so cargo restore asks after the hull exists
        if (uuid in respawning) continue
        val entity = Bukkit.getEntity(uuid)
        // Pickup reused the UUID
        if (entity == null || (holder is HolderId.ItemEntity && entity !is Item)) {
            val hint = services.whereabouts.at(uuid)
            if (hint != null && holder is HolderId.Entity) {
                // Folia global getEntity is often null two regions away; last chunk still hops cargo
                at[uuid] = hint
            } else {
                missing += uuid
            }
            continue
        }
        val loc = entity.location
        at[uuid] = HolderId.Block(WorldId(loc.world.uid), loc.blockX, loc.blockY, loc.blockZ)
        val giving = deltas[holder]?.values?.any { it > 0L } == true
        if (giving && (entity is ItemFrame || entity is ArmorStand)) {
            trackerNeeded += uuid
        }
    }
    return EntityCensus(at, missing, trackerNeeded)
}
