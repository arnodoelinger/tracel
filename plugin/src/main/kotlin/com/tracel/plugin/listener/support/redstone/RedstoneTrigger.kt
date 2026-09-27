package com.tracel.plugin.listener.support.redstone

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.tracel.annotations.Unstable
import com.tracel.model.holder.HolderId
import com.tracel.plugin.util.ExplosionOrigin
import com.tracel.plugin.util.toExplosionOrigin
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.bukkit.Location
import org.bukkit.World

// TODO: rewrite

@Unstable
class RedstoneTrigger {
    private data class Detonation(val origin: ExplosionOrigin, val causedBy: HolderId, val atMillis: Long)

    private val presses: Cache<BlockKey, HolderId> = Caffeine.newBuilder()
        .expireAfterWrite(TRIGGER_WINDOW_MILLIS, TimeUnit.MILLISECONDS)
        .maximumSize(1_000)
        .build()
    private val creeperIgnitions: Cache<UUID, HolderId> = Caffeine.newBuilder()
        .expireAfterWrite(IGNITION_WINDOW_MILLIS, TimeUnit.MILLISECONDS)
        .maximumSize(1_000)
        .build()
    private val detonations = CopyOnWriteArrayList<Detonation>()

    fun recordPress(world: World, x: Int, y: Int, z: Int, causedBy: HolderId) {
        presses.put(BlockKey(world.uid, x, y, z), causedBy)
    }

    fun recordCreeperIgnition(creeper: UUID, causedBy: HolderId) {
        creeperIgnitions.put(creeper, causedBy)
    }

    fun creeperIgnitedBy(creeper: UUID): HolderId? = creeperIgnitions.getIfPresent(creeper)

    fun recentPressNear(world: World, x: Int, y: Int, z: Int): HolderId? {
        for ((dx, dy, dz) in NEIGHBOR_OFFSETS) {
            presses.getIfPresent(BlockKey(world.uid, x + dx, y + dy, z + dz))?.let { return it }
        }
        return null
    }

    fun recordExplosion(location: Location, causedBy: HolderId) {
        val now = System.currentTimeMillis()
        val origin = location.toExplosionOrigin() ?: return
        detonations.add(Detonation(origin, causedBy, now))
        detonations.removeIf { now - it.atMillis > EXPLOSION_CHAIN_WINDOW_MILLIS }
    }

    fun recentExplosionNear(location: Location): HolderId? {
        val world = location.world ?: return null
        val now = System.currentTimeMillis()
        val radiusSq = EXPLOSION_CHAIN_RADIUS * EXPLOSION_CHAIN_RADIUS
        return detonations
            .asSequence()
            .filter { it.origin.world == world.uid && now - it.atMillis <= EXPLOSION_CHAIN_WINDOW_MILLIS }
            .filter { distanceSq(it, location) <= radiusSq }
            .maxByOrNull { it.atMillis }
            ?.causedBy
    }

    private fun distanceSq(detonation: Detonation, location: Location): Double {
        val dx = detonation.origin.x - location.x
        val dy = detonation.origin.y - location.y
        val dz = detonation.origin.z - location.z
        return dx * dx + dy * dy + dz * dz
    }

    private data class BlockKey(val world: UUID, val x: Int, val y: Int, val z: Int)

    private companion object {
        const val TRIGGER_WINDOW_MILLIS = 10_000L
        const val IGNITION_WINDOW_MILLIS = 30_000L
        const val EXPLOSION_CHAIN_WINDOW_MILLIS = 3_000L
        const val EXPLOSION_CHAIN_RADIUS = 6.0
        val NEIGHBOR_OFFSETS = listOf(
            Triple(0, 0, 0),
            Triple(1, 0, 0), Triple(-1, 0, 0),
            Triple(0, 1, 0), Triple(0, -1, 0),
            Triple(0, 0, 1), Triple(0, 0, -1),
        )
    }
}
