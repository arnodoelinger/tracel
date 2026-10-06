package com.tracel.plugin.adapter.entity.link

import com.tracel.annotations.Unstable
import io.papermc.paper.entity.Leashable
import java.util.*
import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import org.bukkit.entity.Player

/** The hitch or entity this one is tied to, or `null` if it is not [Leashable] / not leashed. */
internal fun Entity.leashedTo(): Entity? {
    if (this !is Leashable) return null
    return runCatching { if (isLeashed) leashHolder else null }.getOrNull()
}

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
