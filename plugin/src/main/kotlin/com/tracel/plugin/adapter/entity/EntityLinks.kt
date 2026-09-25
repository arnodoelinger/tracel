package com.tracel.plugin.adapter.entity

import org.bukkit.Location
import com.tracel.annotations.Unstable
import com.tracel.plugin.adapter.entity.special.ShoulderAdapter
import io.papermc.paper.entity.Leashable
import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The hitch or entity this one is tied to, or `null` if it is not [Leashable] / not leashed. */
internal fun Entity.leashedTo(): Entity? {
    if (this !is Leashable) return null
    return runCatching { if (isLeashed) leashHolder else null }.getOrNull()
}

/** The vehicle this entity is sitting in. `null` if it is standing. */
internal fun Entity.ridingOn(): Entity? = runCatching { vehicle }.getOrNull()

/**
 * Drops the leash without a player click.
 *
 * Goes through [SelfManagedLink] so capture does not log this as someone untying the cow.
 */
@Unstable
internal fun Entity.unleash() {
    if (this !is Leashable) return
    if (!runCatching { isLeashed }.getOrDefault(false)) return
    SelfManagedLink.whileLinking { runCatching { setLeashHolder(null) } }
}

/**
 * Marks a leash or mount change as ours, so listeners do not write a second world-log row
 * for a restore that is already applying the recorded link.
 */
internal object SelfManagedLink {
    private val active = ThreadLocal.withInitial { false }

    /** True on the thread that is currently applying a recorded leash or mount. */
    val isOurs: Boolean get() = active.get()

    /** Runs [action] with [isOurs] set. Nested calls keep the outer flag. */
    fun <T> whileLinking(action: () -> T): T {
        val previous = active.get()
        active.set(true)
        try {
            return action()
        } finally {
            active.set(previous)
        }
    }
}

/**
 * Ties this entity to [holder], or unties it when [holder] is null.
 *
 * Resolves [EntityAliases] first: a reused leash knot may not have the uuid the log recorded.
 * False if the holder is not in the world yet — the caller retries after the hitch exists.
 */
internal fun Entity.applyLeash(holder: UUID?): Boolean {
    if (this !is Leashable) return holder == null
    if (holder == null) {
        val heldByPlayer = runCatching { isLeashed && leashHolder is Player }.getOrDefault(false)
        if (!heldByPlayer) unleash()
        return true
    }
    val wanted = EntityAliases.resolve(holder)
    val leashedTo = runCatching { if (isLeashed) leashHolder.uniqueId else null }.getOrNull()
    if (leashedTo == wanted) return true
    val entity = Bukkit.getEntity(wanted)?.takeIf { it.isValid } ?: return false
    return SelfManagedLink.whileLinking { runCatching { setLeashHolder(entity) }.getOrDefault(false) }
}

/**
 * Vanilla keeps one leash hitch per fence and reuses it. A restore that expected the old
 * uuid must still find the live one.
 *
 * The alias lasts as long as that hitch. Gone from the world — gone from the map.
 */
internal object EntityAliases {
    private val byOldId = ConcurrentHashMap<UUID, UUID>()

    /** Records that [recorded] now lives as [actual]. No-op when they are already the same. */
    fun remember(recorded: UUID, actual: UUID) {
        if (recorded != actual) byOldId[recorded] = actual
    }

    /** The UUID to look up in the world. The recorded one if the hitch is gone or never aliased. */
    fun resolve(recorded: UUID): UUID {
        val actual = byOldId[recorded] ?: return recorded
        val live = Bukkit.getEntity(actual)
        if (live == null || !live.isValid) {
            byOldId.remove(recorded, actual)
            return recorded
        }
        return actual
    }
}

/**
 * Puts this entity on [vehicle], or dismounts when [vehicle] is `null`.
 *
 * `false` if the mount is not loaded yet. Capture is suppressed via [SelfManagedLink].
 */
@Unstable
internal fun Entity.applyVehicle(vehicle: UUID?): Boolean {
    val riding = runCatching { this.vehicle?.uniqueId }.getOrNull()
    if (riding == vehicle) return true
    if (vehicle == null) {
        SelfManagedLink.whileLinking { runCatching { leaveVehicle() } }
        return true
    }
    val mount = Bukkit.getEntity(vehicle)?.takeIf { it.isValid } ?: return false
    return SelfManagedLink.whileLinking {
        if (riding != null) runCatching { leaveVehicle() }
        runCatching { mount.addPassenger(this) }.getOrDefault(false)
    }
}

/**
 * Unties every entity currently leashed to this one, in this chunk and its neighbors.
 *
 * Vanilla leash length is under a chunk; +-1 covers a hitch on the chunk edge.
 */
@Unstable
internal fun Entity.unleashHeld() {
    val cx = chunk.x
    val cz = chunk.z
    for (dx in -1..1) {
        for (dz in -1..1) {
            if (!world.isChunkLoaded(cx + dx, cz + dz)) continue
            for (other in world.getChunkAt(cx + dx, cz + dz).entities) {
                if (other.uniqueId == uniqueId) continue
                if (other.leashedTo()?.uniqueId != uniqueId) continue
                other.unleash()
            }
        }
    }
}

/** Clears [uuid] off any online player's shoulder so a restore can spawn the bird in the world. */
internal fun takeOffShoulder(uuid: UUID, near: Location): Boolean = ShoulderAdapter.takeOff(uuid, near)
