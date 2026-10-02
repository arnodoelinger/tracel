package com.tracel.plugin.rollback.structure

import com.tracel.annotations.Unstable
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.holder.HolderId
import com.tracel.model.id.WorldId
import com.tracel.model.world.BlockPos
import com.tracel.plugin.util.ownsChunkAt
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.util.BoundingBox
import java.util.logging.Level
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.floor

private const val WATCH_MARGIN = 3
private const val SEARCH_RADIUS = 8
private const val SEARCH_DOWN = 4
private const val SEARCH_UP = 10
private const val FALL_TOLERANCE = 3
private const val UNDERFOOT_REACH = 8

private val HAZARDS = setOf(
    Material.LAVA,
    Material.FIRE,
    Material.SOUL_FIRE,
    Material.CACTUS,
    Material.MAGMA_BLOCK,
    Material.POWDER_SNOW,
    Material.SWEET_BERRY_BUSH,
    Material.WITHER_ROSE,
    Material.CAMPFIRE,
    Material.SOUL_CAMPFIRE,
    Material.POINTED_DRIPSTONE,
)

/**
 * Get players out of the way of what [applied] just wrote.
 *
 * Buried in a block, standing in lava or fire, or left over a drop the rollback dug: moved to the
 * nearest cell with air at the feet and head and honest ground below. Everyone else stays put.
 */
@Unstable
internal suspend fun StructureRestorer.rescuePlayers(applied: List<StructureStep>) {
    val writes = applied.filterIsInstance<StructureStep.SetBlock>().map { it.at }
    if (writes.isEmpty()) return
    val byWorld = writes.groupBy { it.world }
    val online = Bukkit.getOnlinePlayers().map { it.uniqueId }
    if (online.isEmpty()) return

    coroutineScope {
        online.map { uuid ->
            async {
                try {
                    withContext(services.schedulers.entity(uuid)) {
                        val player = Bukkit.getPlayer(uuid) ?: return@withContext
                        val near = byWorld[WorldId(player.world.uid)] ?: return@withContext
                        rescue(player, near)
                    }
                } catch (failure: Throwable) {
                    if (failure is CancellationException) throw failure
                    logger.log(Level.WARNING, "could not check a player's safety after a restore", failure)
                }
            }
        }.awaitAll()
    }
}

/**
 * A player who logged out inside what a rollback has since written.
 *
 * Nothing says what was written while they were away, so only the plain dangers are checked: buried,
 * or standing in something that hurts. A hole under them is left to vanilla.
 */
internal suspend fun StructureRestorer.rescueJoined(player: Player) {
    try {
        withContext(services.schedulers.entity(player.uniqueId)) {
            if (!player.isOnline || player.isDead || player.gameMode == GameMode.SPECTATOR) return@withContext
            val here = player.location
            if (!endangered(here.world, player.boundingBox, flying = true, underfoot = false)) return@withContext
            move(player, here)
        }
    } catch (failure: Throwable) {
        if (failure is CancellationException) throw failure
        logger.log(Level.WARNING, "could not check a joining player's safety", failure)
    }
}

private suspend fun StructureRestorer.move(player: Player, here: Location) {
    val spot = safeSpotNear(here.world, here) ?: return
    if (player.teleportAsync(spot, PlayerTeleportEvent.TeleportCause.PLUGIN).await()) {
        player.fallDistance = 0f
        player.fireTicks = 0
    }
}

private suspend fun StructureRestorer.rescue(player: Player, writes: List<BlockPos>) {
    if (player.isDead || player.gameMode == GameMode.SPECTATOR) return
    val here = player.location
    val world = here.world
    val px = here.blockX
    val py = here.blockY
    val pz = here.blockZ
    val touched = writes.any {
        it.x in px - WATCH_MARGIN..px + WATCH_MARGIN &&
                it.z in pz - WATCH_MARGIN..pz + WATCH_MARGIN &&
                it.y in py - UNDERFOOT_REACH..py + WATCH_MARGIN + 2
    }
    if (!touched) return

    val flying = player.isFlying || player.isGliding
    val underfoot = writes.any {
        it.x in px - 1..px + 1 && it.z in pz - 1..pz + 1 && it.y in py - UNDERFOOT_REACH..py
    }
    if (!endangered(world, player.boundingBox, flying, underfoot)) return

    // Folia moves the player across regions on its own; the check above is stale by then, which is fine
    move(player, here)
}

private suspend fun <T> StructureRestorer.inChunk(world: World, x: Int, y: Int, z: Int, work: () -> T): T =
    if (ownsChunkAt(world, x, z)) work()
    else withContext(services.schedulers.region(HolderId.Block(WorldId(world.uid), x, y, z))) { work() }

private fun chunkOf(x: Int, z: Int): Long = (x shr 4).toLong() shl 32 or ((z shr 4).toLong() and 0xFFFFFFFFL)

private fun cellsByChunk(box: BoundingBox): Collection<List<IntArray>> {
    val groups = HashMap<Long, MutableList<IntArray>>()
    for (x in floor(box.minX).toInt()..floor(box.maxX - 1e-4).toInt())
        for (y in floor(box.minY).toInt()..floor(box.maxY - 1e-4).toInt())
            for (z in floor(box.minZ).toInt()..floor(box.maxZ - 1e-4).toInt())
                groups.getOrPut(chunkOf(x, z)) { ArrayList() } += intArrayOf(x, y, z)
    return groups.values
}

private suspend fun StructureRestorer.endangered(
    world: World,
    box: BoundingBox,
    flying: Boolean,
    underfoot: Boolean,
): Boolean {
    for (cells in cellsByChunk(box)) {
        val (x, y, z) = cells.first()
        val bad = inChunk(world, x, y, z) {
            cells.any { (cx, cy, cz) ->
                val block = world.getBlockAt(cx, cy, cz)
                block.type in HAZARDS || blocksBody(block, box)
            }
        }
        if (bad) return true
    }
    if (flying || !underfoot) return false
    return !hasGround(world, box)
}

private suspend fun StructureRestorer.hasGround(world: World, box: BoundingBox): Boolean {
    val feet = floor(box.minY - 1e-4).toInt()
    val groups = HashMap<Long, MutableList<IntArray>>()
    for (x in floor(box.minX).toInt()..floor(box.maxX).toInt())
        for (z in floor(box.minZ).toInt()..floor(box.maxZ).toInt())
            groups.getOrPut(chunkOf(x, z)) { ArrayList() } += intArrayOf(x, z)
    for (columns in groups.values) {
        val (x, z) = columns.first()
        val grounded = inChunk(world, x, feet, z) {
            // A chunk nobody loaded is no evidence of a hole
            if (!world.isChunkLoaded(x shr 4, z shr 4)) return@inChunk true
            (0..FALL_TOLERANCE).any { dy ->
                columns.any { (cx, cz) ->
                    val block = world.getBlockAt(cx, feet - dy, cz)
                    block.type !in HAZARDS && (block.isLiquid || !block.isPassable)
                }
            }
        }
        if (grounded) return true
    }
    return false
}

private fun blocksBody(block: Block, box: BoundingBox): Boolean {
    if (block.isPassable) return false
    val shape = block.collisionShape
    return shape.boundingBoxes.any { it.shift(block.x.toDouble(), block.y.toDouble(), block.z.toDouble()).overlaps(box) }
}

private suspend fun StructureRestorer.safeSpotNear(world: World, from: Location): Location? {
    val cx = from.blockX
    val cy = from.blockY
    val cz = from.blockZ
    var best: Location? = null
    var bestScore = Double.MAX_VALUE
    for (chunkX in ((cx - SEARCH_RADIUS) shr 4)..((cx + SEARCH_RADIUS) shr 4))
        for (chunkZ in ((cz - SEARCH_RADIUS) shr 4)..((cz + SEARCH_RADIUS) shr 4)) {
            val (spot, score) = inChunk(world, chunkX shl 4, cy, chunkZ shl 4) {
                if (!world.isChunkLoaded(chunkX, chunkZ)) null to Double.MAX_VALUE
                else searchChunk(world, from, chunkX, chunkZ)
            }
            if (spot != null && score < bestScore) {
                best = spot
                bestScore = score
            }
        }
    return best
}

private fun searchChunk(world: World, from: Location, chunkX: Int, chunkZ: Int): Pair<Location?, Double> {
    val cx = from.blockX
    val cy = from.blockY
    val cz = from.blockZ
    var best: Location? = null
    var bestScore = Double.MAX_VALUE
    for (x in maxOf(chunkX shl 4, cx - SEARCH_RADIUS)..minOf((chunkX shl 4) + 15, cx + SEARCH_RADIUS))
        for (z in maxOf(chunkZ shl 4, cz - SEARCH_RADIUS)..minOf((chunkZ shl 4) + 15, cz + SEARCH_RADIUS)) {
            val dx = x - cx
            val dz = z - cz
            for (dy in -SEARCH_DOWN..SEARCH_UP) {
                val y = cy + dy
                if (y <= world.minHeight || y + 1 >= world.maxHeight) continue
                val score = (dx * dx + dz * dz + dy * dy * 2).toDouble()
                if (score >= bestScore || !standable(world, x, y, z)) continue
                bestScore = score
                best = Location(world, x + 0.5, y.toDouble(), z + 0.5, from.yaw, from.pitch)
            }
        }
    return best to bestScore
}

private fun standable(world: World, x: Int, y: Int, z: Int): Boolean {
    val feet = world.getBlockAt(x, y, z)
    val head = world.getBlockAt(x, y + 1, z)
    val floor = world.getBlockAt(x, y - 1, z)
    if (!clear(feet) || !clear(head)) return false
    if (floor.type in HAZARDS || floor.isLiquid || floor.isPassable) return false
    return floor.collisionShape.boundingBoxes.any { it.maxY >= 1.0 - 1e-4 }
}

private fun clear(block: Block): Boolean =
    block.isPassable && !block.isLiquid && block.type !in HAZARDS
