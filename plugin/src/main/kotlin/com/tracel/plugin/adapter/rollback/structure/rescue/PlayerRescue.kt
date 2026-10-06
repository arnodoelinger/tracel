package com.tracel.plugin.adapter.rollback.structure.rescue

import com.tracel.annotations.Unstable
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.world.BlockPos
import com.tracel.model.world.WorldId
import com.tracel.plugin.rollback.structure.StructureRestorer
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerTeleportEvent
import java.util.*
import java.util.logging.Level
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

/**
 * Get players out of the way of what [applied] just wrote.
 *
 * Buried in a block, standing in lava or fire, or left over a drop the rollback dug: moved to the
 * nearest cell with air at the feet and head and honest ground below. Everyone else stays put.
 */
@Unstable
internal fun StructureRestorer.rescuePlayers(applied: List<StructureStep>) {
    val writes = applied.filterIsInstance<StructureStep.SetBlock>().map { it.at }
    if (writes.isEmpty()) return
    val byWorld = writes.groupBy { it.world }
    val online = Bukkit.getOnlinePlayers().map { it.uniqueId }
    if (online.isEmpty()) return

    services.scope.launch {
        sweep(online, byWorld)
        for (wait in FOLLOW_UPS_MILLIS) {
            delay(wait.milliseconds)
            sweep(online, byWorld)
        }
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

private suspend fun StructureRestorer.sweep(online: List<UUID>, byWorld: Map<WorldId, List<BlockPos>>) {
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

private suspend fun StructureRestorer.move(player: Player, here: Location, up: Int = SEARCH_UP) {
    val spot = safeSpotNear(here.world, here, up) ?: surfaceSpotNear(here.world, here)
    ?: return
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
    val fell = if (player.fallDistance > FALLING) player.fallDistance.toInt() else 0
    val touched = writes.any {
        it.x in px - WATCH_MARGIN..px + WATCH_MARGIN &&
                it.z in pz - WATCH_MARGIN..pz + WATCH_MARGIN &&
                it.y in py - UNDERFOOT_REACH..py + WATCH_MARGIN + 2 + fell
    }
    if (!touched) return

    val flying = player.isFlying || player.isGliding
    val underfoot = writes.any {
        it.x in px - 1..px + 1 && it.z in pz - 1..pz + 1 && it.y in py - UNDERFOOT_REACH..py + 1 + fell
    }
    if (!endangered(world, player.boundingBox, flying, underfoot)) return

    // Folia moves the player across regions on its own; the check above is stale by then, which is fine
    move(player, here, SEARCH_UP + fell)
}
