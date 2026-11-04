package com.tracel.plugin.bukkit.world

import com.tracel.engine.log.lookup.LookupRegion
import com.tracel.model.world.BlockPos
import com.tracel.model.world.WorldId
import com.tracel.plugin.command.args.scope.LookupScope
import com.tracel.plugin.util.geometry.ExplosionOrigin
import com.tracel.plugin.util.geometry.lookupRegion
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import java.util.*

/** The live world for [id], or `null` if it is not loaded. */
fun worldOf(id: WorldId): World? = Bukkit.getWorld(id.uuid)

/** The live world for [uuid], or `null` if it is not loaded. */
fun worldOf(uuid: UUID): World? = Bukkit.getWorld(uuid)

/** Whether this player is on the server right now. */
fun playerIsOnline(uuid: UUID): Boolean = Bukkit.getPlayer(uuid) != null

/** The live player, or `null` if they are offline. */
fun playerOf(uuid: UUID): Player? = Bukkit.getPlayer(uuid)

/** @return `true` if this thread owns the chunk at these block coordinates. */
internal fun ownsChunkAt(world: World, blockX: Int, blockZ: Int): Boolean =
    runCatching { Bukkit.isOwnedByCurrentRegion(world, blockX shr 4, blockZ shr 4) }.getOrDefault(false)

/**
 * @return `true` if this thread owns the chunk [at] is in.
 *
 * Missing world is not owned.
 */
internal fun ownsChunkAt(at: BlockPos): Boolean {
    val world = Bukkit.getWorld(at.world.uuid) ?: return false
    return ownsChunkAt(world, at.x, at.z)
}

/** Location of an explosion. */
internal fun Location.toExplosionOrigin(): ExplosionOrigin? {
    val world = world ?: return null
    return ExplosionOrigin(world.uid, x, y, z)
}

/** Converts a `Bukkit` [World] instance to a domain [WorldId]. */
fun World?.toWorldId(): WorldId? = this?.uid?.let(::WorldId)

/** Creates a [LookupRegion] relative to this [Location]. */
fun Location.toLookupRegion(
    scope: LookupScope?,
    horizontalOnly: Boolean = false
): LookupRegion? {
    if (scope == null) return null
    val targetWorld = world ?: return null
    return lookupRegion(WorldId(targetWorld.uid), blockX, blockY, blockZ, scope, horizontalOnly)
}
