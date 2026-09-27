package com.tracel.plugin.listener.support.drop

import com.tracel.annotations.Unstable
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.bukkit.World
import java.util.concurrent.atomic.AtomicLong

/** Claim windows that bind vanilla item spawns to the block they left. */
@Unstable
class BlockDrop {
    data class ClaimedDrop(val itemKey: ItemKey, val quantity: Long, val entity: HolderId)
    data class ReleaseResult(val unclaimed: Map<ItemKey, Long>, val claimed: List<ClaimedDrop>)

    private class Release(val world: UUID, val x: Int, val y: Int, val z: Int, val remaining: ConcurrentHashMap<ItemKey, Long>) {
        val claimed = CopyOnWriteArrayList<ClaimedDrop>()
    }

    private val releases = ConcurrentHashMap<Long, Release>()
    private val nextToken = AtomicLong()

    /** Open the claim window before [credit]. */
    fun open(world: World, x: Int, y: Int, z: Int): Long {
        val token = nextToken.incrementAndGet()
        releases[token] = Release(world.uid, x, y, z, ConcurrentHashMap())
        return token
    }

    /** Believed stock, filled after the storage hop; the window is already open. */
    fun credit(token: Long, believed: Map<ItemKey, Long>) {
        val release = releases[token] ?: return
        for ((itemKey, quantity) in believed) release.remaining[itemKey] = quantity
    }

    /**
     * Determines if there is a nearby release in the specified location within
     * a predefined radius.
     */
    fun hasNearbyRelease(world: World, x: Double, y: Double, z: Double): Boolean {
        val radiusSq = MATCH_RADIUS * MATCH_RADIUS
        return releases.values.any { it.world == world.uid && distanceSq(it, x, y, z) <= radiusSq }
    }

    /** The window a drop spawning at this point will most likely be claimed by, or `null` if none is open near. */
    fun nearestToken(world: World, x: Double, y: Double, z: Double): Long? {
        val radiusSq = MATCH_RADIUS * MATCH_RADIUS
        return releases.entries
            .filter { (_, release) -> release.world == world.uid && distanceSq(release, x, y, z) <= radiusSq }
            .minByOrNull { (_, release) -> distanceSq(release, x, y, z) }
            ?.key
    }

    /**
     * Prefer a nearby release that believed it held this item over a closer empty one,
     * so chest contents are not credited to the stone beside it.
     */
    fun claim(world: World, x: Double, y: Double, z: Double, itemKey: ItemKey, quantity: Long, entity: HolderId): Long {
        val radiusSq = MATCH_RADIUS * MATCH_RADIUS
        val nearby = releases.values.filter { it.world == world.uid && distanceSq(it, x, y, z) <= radiusSq }
        if (nearby.isEmpty()) return 0L

        val backed = nearby.filter { (it.remaining[itemKey] ?: 0L) > 0L }.minByOrNull { distanceSq(it, x, y, z) }
        if (backed != null) {
            var claimed = 0L
            backed.remaining.compute(itemKey) { _, left ->
                val have = left ?: 0L
                claimed = minOf(have, quantity)
                (have - claimed).takeIf { it > 0 }
            }
            if (claimed > 0) backed.claimed += ClaimedDrop(itemKey, claimed, entity)
            if (claimed >= quantity) return claimed

            // Surplus still claims here so mint-then-move can attach it to this block, not a mint from nowhere
            backed.claimed += ClaimedDrop(itemKey, quantity - claimed, entity)
            return quantity
        }

        val nearest = nearby.minByOrNull { distanceSq(it, x, y, z) } ?: return 0L
        nearest.claimed += ClaimedDrop(itemKey, quantity, entity)
        return quantity
    }

    /** Close the window; leftover believed stock is the burn remainder (vanilla never dropped it). */
    fun finish(token: Long): ReleaseResult {
        val release = releases.remove(token) ?: return ReleaseResult(emptyMap(), emptyList())
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
