package com.tracel.plugin.listener.redstone

import com.tracel.model.holder.HolderId
import org.bukkit.Location
import org.bukkit.World
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Remembers, briefly, who last pressed a button / lever / pressure plate at a given block, who
 * last lit a specific creeper with flint and steel, and where the most recent attributed
 * explosions went off — the direct triggers [ExplosionCaptureListener][com.tracel.plugin.listener.explosion.ExplosionCaptureListener]
 * attributes an otherwise-unattributed explosion to.
 */
class RedstoneTriggerTracker {
    private data class Press(val causedBy: HolderId, val atMillis: Long)
    private data class Detonation(val world: UUID, val x: Double, val y: Double, val z: Double, val causedBy: HolderId, val atMillis: Long)

    private val presses = ConcurrentHashMap<BlockKey, Press>()
    private val creeperIgnitions = ConcurrentHashMap<UUID, Press>()
    private val detonations = CopyOnWriteArrayList<Detonation>()

    fun recordPress(world: World, x: Int, y: Int, z: Int, causedBy: HolderId) {
        val now = System.currentTimeMillis()
        presses[BlockKey(world.uid, x, y, z)] = Press(causedBy, now)
        if (presses.size > PRUNE_THRESHOLD) {
            presses.entries.removeIf { now - it.value.atMillis > TRIGGER_WINDOW_MILLIS }
        }
    }

    fun recordCreeperIgnition(creeper: UUID, causedBy: HolderId) {
        val now = System.currentTimeMillis()
        creeperIgnitions[creeper] = Press(causedBy, now)
        if (creeperIgnitions.size > PRUNE_THRESHOLD) {
            creeperIgnitions.entries.removeIf { now - it.value.atMillis > IGNITION_WINDOW_MILLIS }
        }
    }

    /**
     * A recent flint-and-steel ignition for this exact creeper, if any — a non-destructive read,
     * not consumed on lookup.
     */
    fun creeperIgnitedBy(creeper: UUID): HolderId? {
        val press = creeperIgnitions[creeper] ?: return null
        return press.takeIf { System.currentTimeMillis() - it.atMillis <= IGNITION_WINDOW_MILLIS }?.causedBy
    }

    /** Who pressed a button / lever / plate directly touching this block within the trigger window, if any. */
    fun recentPressNear(world: World, x: Int, y: Int, z: Int): HolderId? {
        val now = System.currentTimeMillis()
        for ((dx, dy, dz) in NEIGHBOR_OFFSETS) {
            val press = presses[BlockKey(world.uid, x + dx, y + dy, z + dz)] ?: continue
            if (now - press.atMillis <= TRIGGER_WINDOW_MILLIS) return press.causedBy
        }
        return null
    }

    /**
     * Records where an explosion whose cause we did manage to resolve just went off, so a TNT
     * caught in its blast can still inherit the attribution a moment later.
     */
    fun recordExplosion(location: Location, causedBy: HolderId) {
        val now = System.currentTimeMillis()
        val world = location.world ?: return
        detonations.add(Detonation(world.uid, location.x, location.y, location.z, causedBy, now))
        detonations.removeIf { now - it.atMillis > EXPLOSION_CHAIN_WINDOW_MILLIS }
    }

    /** The most recent attributed explosion within plausible blast range of this location, if any. */
    fun recentExplosionNear(location: Location): HolderId? {
        val world = location.world ?: return null
        val now = System.currentTimeMillis()
        val radiusSq = EXPLOSION_CHAIN_RADIUS * EXPLOSION_CHAIN_RADIUS
        return detonations
            .asSequence()
            .filter { it.world == world.uid && now - it.atMillis <= EXPLOSION_CHAIN_WINDOW_MILLIS }
            .filter { distanceSq(it, location) <= radiusSq }
            .maxByOrNull { it.atMillis }
            ?.causedBy
    }

    private fun distanceSq(detonation: Detonation, location: Location): Double {
        val dx = detonation.x - location.x
        val dy = detonation.y - location.y
        val dz = detonation.z - location.z
        return dx * dx + dy * dy + dz * dz
    }

    private data class BlockKey(val world: UUID, val x: Int, val y: Int, val z: Int)

    private companion object {
        const val TRIGGER_WINDOW_MILLIS = 10_000L
        const val IGNITION_WINDOW_MILLIS = 30_000L
        const val EXPLOSION_CHAIN_WINDOW_MILLIS = 3_000L
        const val EXPLOSION_CHAIN_RADIUS = 6.0
        const val PRUNE_THRESHOLD = 256
        val NEIGHBOR_OFFSETS = listOf(
            Triple(0, 0, 0),
            Triple(1, 0, 0), Triple(-1, 0, 0),
            Triple(0, 1, 0), Triple(0, -1, 0),
            Triple(0, 0, 1), Triple(0, 0, -1),
        )
    }
}
