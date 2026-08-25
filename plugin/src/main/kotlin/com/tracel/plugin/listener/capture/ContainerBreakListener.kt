package com.tracel.plugin.listener.capture

import com.tracel.annotations.Assumption
import com.tracel.annotations.CauseKind
import com.tracel.annotations.Fallback
import com.tracel.engine.balance.InventoryDelta
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.plugin.TracelServices
import com.tracel.plugin.convert.toHolderId
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bukkit.Bukkit
import org.bukkit.block.Container
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Releases a container's believed contents as a "MOVE" straight to the real ground-item entities
 * the break spawns, instead of the disconnected "BURN" + "MINT" pair a blind release would cause.
 *
 * The inventory is cleared synchronously before vanilla's own spill logic runs, then the ground
 * items are spawned back manually via [SelfManagedSpawnGuard] once the ledger read confirms what
 * the container held.
 *
 */
class ContainerBreakListener(internal val services: TracelServices) : Listener {
    private val logger = Logger.getLogger(ContainerBreakListener::class.java.name)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        val state = event.block.getState(false)
        if (state !is Container) return

        val holder = event.block.toHolderId()
        val causedBy = HolderId.Player(event.player.uniqueId)
        val epochMillis = System.currentTimeMillis()

        onBreakGuarded(event, holder, causedBy, epochMillis)
    }

    @Assumption
    @Fallback("onBreakFallback")
    internal fun onBreak(event: BlockBreakEvent, holder: HolderId.Block, causedBy: HolderId.Player, epochMillis: Long) {
        val state = event.block.getState(false) as Container
        val dropLocation = event.block.location.add(0.5, 0.5, 0.5)

        // Nothing vanilla drops for the contents from here on — see the class doc for why this
        // has to happen now, synchronously, rather than after the async ledger read below.
        state.inventory.clear()
        services.vanillaAssumptions.watch(ContainerBreakListener_onBreakAssumptionId, holder.world.uuid, holder.x, holder.y, holder.z)

        services.scope.launch {
            try {
                val believed = services.atomically { services.ledger.totalsAt(holder) }
                if (believed.isNotEmpty()) {
                    val spawned = withContext(services.schedulers.region(holder)) {
                        val world = Bukkit.getWorld(holder.world.uuid)
                        if (world == null) {
                            logger.warning("world for $holder unloaded before its broken contents could be respawned — contents lost")
                            return@withContext emptyList()
                        }
                        believed.flatMap { (itemKey, qty) -> services.spawnAsRelease(itemKey, qty.raw, world, dropLocation) }
                    }

                    val deltas = believed.map { (itemKey, qty) -> InventoryDelta(holder, itemKey, -qty.raw) } + spawned
                    services.atomically {
                        services.capture.record(deltas, epochMillis, CauseKind.BLOCK_BREAK, causedBy)
                    }
                }
            } catch (e: IllegalStateException) {
                logger.log(Level.FINE, "untracked material in container broken by $causedBy, not recorded", e)
            }
            services.differ.forget(holder)
        }
    }

    /** The always-safe fallback: an ordinary unmatched-loss release, no suppression, no manual respawn. */
    internal fun onBreakFallback(event: BlockBreakEvent, holder: HolderId.Block, causedBy: HolderId.Player, epochMillis: Long) {
        services.gate.release(
            cause = CauseKind.BLOCK_BREAK,
            causedBy = causedBy,
            epochMillis = epochMillis,
            from = holder,
            to = HolderId.Sink(SinkKind.UNATTRIBUTED),
        )
        services.differ.forget(holder)
    }
}
