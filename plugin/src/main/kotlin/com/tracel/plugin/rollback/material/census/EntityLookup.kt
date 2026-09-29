package com.tracel.plugin.rollback.material.census

import com.tracel.model.holder.HolderId
import com.tracel.model.id.WorldId
import com.tracel.model.item.ItemKey
import com.tracel.plugin.adapter.world.worldOf
import com.tracel.plugin.rollback.material.MaterialRestorer
import com.tracel.plugin.util.namedByEntity
import com.tracel.plugin.util.regionKey
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import org.bukkit.Bukkit
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Item
import org.bukkit.entity.ItemFrame
import java.util.*

/** Which UUID holders are gone. */
internal suspend fun MaterialRestorer.findVanished(
    holders: Set<HolderId>,
): Set<HolderId> {
    val byUuid = holders.filterTo(HashSet()) { it.namedByEntity() }
    if (byUuid.isEmpty()) return emptySet()
    return withContext(services.schedulers.global) {
        byUuid.filterTo(HashSet()) { holder ->
            when (holder) {
                is HolderId.ItemEntity -> Bukkit.getEntity(holder.uuid) !is Item
                is HolderId.Entity -> false
                is HolderId.PlacedEntity -> false
                else -> false
            }
        }
    }.let { suspects -> confirmGone(suspects) }
}

/**
 * The global thread's word for "gone" is not enough: `Folia` often answers null for an entity two
 * regions away, and a pile marked vanished is compensated while it still lies there. Ask again where
 * it was last seen, with its chunk and entities loaded.
 */
internal suspend fun MaterialRestorer.confirmGone(suspects: Set<HolderId>): Set<HolderId> {
    if (suspects.isEmpty()) return suspects
    val hinted = HashMap<HolderId, HolderId.Block>()
    val gone = HashSet<HolderId>()
    for (holder in suspects) {
        val uuid = (holder as? HolderId.ItemEntity)?.uuid
        val hint = uuid?.let { services.groundWhereabouts.at(it) }
        if (hint == null) gone += holder else hinted[holder] = hint
    }
    val found = coroutineScope {
        hinted.entries.groupBy { it.value.regionKey() }.values.map { group ->
            async {
                val at = group.first().value
                val world = worldOf(at.world) ?: return@async emptyList()
                val chunks = group.mapTo(HashSet()) { (it.value.x shr 4) to (it.value.z shr 4) }
                chunks.map { (cx, cz) -> async { runCatching { world.getChunkAtAsync(cx, cz).await() } } }.awaitAll()
                withContext(services.schedulers.region(at)) {
                    for ((cx, cz) in chunks) runCatching { world.getChunkAt(cx, cz).entities }
                    group.filter { (holder, _) -> Bukkit.getEntity((holder as HolderId.ItemEntity).uuid) is Item }
                        .map { it.key }
                }
            }
        }.awaitAll().flatten()
    }
    for ((holder, _) in hinted) if (holder !in found) gone += holder
    return gone
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
