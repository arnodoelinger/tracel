package com.tracel.plugin.listener.explosion

import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import org.bukkit.World
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Lets an explosion's container / block releases claim credit for the specific item entities
 * vanilla's own explosion drop logic actually spawns, instead of the disconnected `BURN` + `MINT`
 * pair a blind release would otherwise produce.
 */
class ExplosionDropCorrelator {
    data class ClaimedDrop(val itemKey: ItemKey, val quantity: Long, val entity: HolderId)
    data class ReleaseResult(val unclaimed: Map<ItemKey, Long>, val claimed: List<ClaimedDrop>)

    private class Release(val world: UUID, val x: Int, val y: Int, val z: Int, val remaining: ConcurrentHashMap<ItemKey, Long>) {
        val claimed = CopyOnWriteArrayList<ClaimedDrop>()
    }

    private val releases = ConcurrentHashMap<HolderId, Release>()

    /** Registered once a release's believed contents are known, after the async ledger read. */
    fun resolve(holder: HolderId, world: World, x: Int, y: Int, z: Int, believed: Map<ItemKey, Long>) {
        if (believed.isEmpty()) return
        releases[holder] = Release(world.uid, x, y, z, ConcurrentHashMap(believed))
    }

    /** Cheap presence check a fresh spawn can use to decide whether deferring itself is worth it at all. */
    fun hasNearbyRelease(world: World, x: Double, y: Double, z: Double): Boolean {
        val radiusSq = MATCH_RADIUS * MATCH_RADIUS
        return releases.values.any { it.world == world.uid && distanceSq(it, x, y, z) <= radiusSq }
    }

    /** Atomically claims up to [quantity] of [itemKey] from the nearest matching release, if any; returns how much. */
    fun claim(world: World, x: Double, y: Double, z: Double, itemKey: ItemKey, quantity: Long, entity: HolderId): Long {
        val radiusSq = MATCH_RADIUS * MATCH_RADIUS
        val candidate = releases.values
            .asSequence()
            .filter { it.world == world.uid && (it.remaining[itemKey] ?: 0L) > 0L && distanceSq(it, x, y, z) <= radiusSq }
            .minByOrNull { distanceSq(it, x, y, z) }
            ?: return 0L

        var claimed = 0L
        candidate.remaining.compute(itemKey) { _, left ->
            val have = left ?: 0L
            claimed = minOf(have, quantity)
            (have - claimed).takeIf { it > 0 }
        }
        if (claimed > 0) candidate.claimed += ClaimedDrop(itemKey, claimed, entity)
        return claimed
    }

    /** Ends this release's claim window — everything still unclaimed is the "vanilla didn't drop it" remainder. */
    fun finish(holder: HolderId): ReleaseResult {
        val release = releases.remove(holder) ?: return ReleaseResult(emptyMap(), emptyList())
        return ReleaseResult(release.remaining.toMap(), release.claimed.toList())
    }

    private fun distanceSq(release: Release, x: Double, y: Double, z: Double): Double {
        val dx = release.x + 0.5 - x
        val dy = release.y + 0.5 - y
        val dz = release.z + 0.5 - z
        return dx * dx + dy * dy + dz * dz
    }

    private companion object {
        const val MATCH_RADIUS = 1.5
    }
}
