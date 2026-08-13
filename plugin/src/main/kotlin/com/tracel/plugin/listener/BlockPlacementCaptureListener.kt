package com.tracel.plugin.listener

import com.tracel.annotations.Assumption
import com.tracel.annotations.CauseKind
import com.tracel.annotations.Fallback
import com.tracel.engine.balance.InventoryDelta
import com.tracel.engine.balance.releaseDeltas
import com.tracel.model.holder.HolderId
import com.tracel.plugin.TracelServices
import com.tracel.plugin.convert.toItemKey
import com.tracel.plugin.convert.toPlacedBlockId
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Captures a block's own existence as an item — separate from, and independent of,
 * [ContainerBreakListener] (which handles a container's contents).
 *
 * A chest place / break fires both listeners; neither knows about the other, since
 * they track different [HolderId] variants at the same coordinates without colliding.
 */
class BlockPlacementCaptureListener(internal val services: TracelServices) : Listener {
    private val logger = Logger.getLogger(BlockPlacementCaptureListener::class.java.name)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        val itemKey = event.itemInHand.toItemKey()
        val playerHolder = HolderId.Player(event.player.uniqueId)
        val placedHolder = event.blockPlaced.toPlacedBlockId()
        val epochMillis = System.currentTimeMillis()
        val deltas = listOf(InventoryDelta(playerHolder, itemKey, -1L), InventoryDelta(placedHolder, itemKey, 1L))

        services.scope.launch {
            try {
                withContext(services.schedulers.storage) {
                    services.capture.record(deltas, epochMillis, CauseKind.PLAYER_ACTION, playerHolder)
                }
            } catch (e: IllegalStateException) {
                logger.log(Level.FINE, "untracked material placed by $playerHolder, not recorded.", e)
            }
        }
    }

    /**
     * A placed block only has a real `PlacedBlock -> item-entity` "MOVE" to correlate when the
     * break is actually going to drop itself (silk touch, or an unconditional-self-drop block
     * like a torch or flower) — unlike a container's contents, most breaks drop something else
     * entirely (ore -> raw material, leaves -> saplings, the wrong tool -> nothing at all), and
     * in that case the placed block's own item genuinely ceased to exist rather than moved
     * anywhere: whatever does physically drop is an unrelated new item, honestly captured on
     * its own by [ItemEntityCaptureListener].
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        val placedHolder = event.block.toPlacedBlockId()
        val causedBy = HolderId.Player(event.player.uniqueId)
        val epochMillis = System.currentTimeMillis()

        val tool = event.player.inventory.itemInMainHand
        val drops = event.block.getDrops(tool, event.player)
        val selfDrops = drops.singleOrNull()?.type == event.block.type

        if (!selfDrops) {
            onBreakFallback(event, placedHolder, causedBy, epochMillis)
            return
        }

        onBreakRiskyGuarded(event, placedHolder, causedBy, epochMillis)
    }

    @Assumption
    @Fallback("onBreakFallback")
    internal fun onBreak(event: BlockBreakEvent, placedHolder: HolderId.PlacedBlock, causedBy: HolderId.Player, epochMillis: Long) {
        val world = event.block.world
        val dropLocation = event.block.location.add(0.5, 0.5, 0.5)
        event.isDropItems = false
        services.vanillaAssumptions.watch(BlockPlacementCaptureListener_onBreakRiskyAssumptionId, placedHolder.world.uuid, placedHolder.x, placedHolder.y, placedHolder.z)

        services.scope.launch {
            try {
                val believed = withContext(services.schedulers.storage) { services.ledger.totalsAt(placedHolder) }
                if (believed.isNotEmpty()) {
                    val spawned = withContext(services.schedulers.region(HolderId.Block(placedHolder.world, placedHolder.x, placedHolder.y, placedHolder.z))) {
                        believed.flatMap { (itemKey, qty) -> services.spawnAsRelease(itemKey, qty.raw, world, dropLocation) }
                    }
                    val deltas = believed.map { (itemKey, qty) -> InventoryDelta(placedHolder, itemKey, -qty.raw) } + spawned
                    withContext(services.schedulers.storage) {
                        services.capture.record(deltas, epochMillis, CauseKind.BLOCK_BREAK, causedBy)
                    }
                }
            } catch (e: IllegalStateException) {
                logger.log(Level.FINE, "untracked placed block broken at $placedHolder, not recorded", e)
            }
        }
    }

    internal fun onBreakFallback(event: BlockBreakEvent, placedHolder: HolderId.PlacedBlock, causedBy: HolderId.Player, epochMillis: Long) {
        services.scope.launch {
            try {
                withContext(services.schedulers.storage) {
                    val deltas = services.ledger.releaseDeltas(placedHolder)
                    if (deltas.isEmpty()) return@withContext
                    services.capture.record(deltas, epochMillis, CauseKind.BLOCK_BREAK, causedBy)
                }
            } catch (e: IllegalStateException) {
                logger.log(Level.FINE, "untracked placed block broken at $placedHolder, not recorded", e)
            }
        }
    }
}
