package com.tracel.plugin.listener

import com.tracel.annotations.CauseKind
import com.tracel.engine.balance.InventoryDelta
import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.item.ItemKey
import com.tracel.plugin.TracelServices
import com.tracel.plugin.convert.toItemKey
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bukkit.Bukkit
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.ItemDespawnEvent
import org.bukkit.event.entity.ItemMergeEvent
import org.bukkit.event.entity.ItemSpawnEvent
import org.bukkit.plugin.Plugin
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Captures ground items — a `Bukkit` `Item` entity is the one holder in this codebase whose
 * exact contents are always known directly from the event that touches it.
 */
class ItemEntityCaptureListener(
    private val services: TracelServices,
    private val plugin: Plugin,
) : Listener {
    private val logger = Logger.getLogger(ItemEntityCaptureListener::class.java.name)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSpawn(event: ItemSpawnEvent) {
        if (services.selfManagedSpawns.isSelfManagedSpawn) return
        val item = event.entity
        val itemKey = item.itemStack.toItemKey()
        val qty = item.itemStack.amount.toLong()
        val groundHolder = HolderId.ItemEntity(item.uniqueId)
        val thrower = item.thrower
        val epochMillis = System.currentTimeMillis()

        // Cheap on the fast path
        run {
            val spawnLoc = item.location
            services.vanillaAssumptions.checkSurpriseSpawn(spawnLoc.world.uid, spawnLoc.x, spawnLoc.y, spawnLoc.z)
        }

        // A world-caused spawn (no thrower) landing right next to a very-recent explosion release
        // might be exactly the item that explosion's blast decided to let survive.
        val loc = item.location
        if (thrower == null && services.explosionDrops.hasNearbyRelease(loc.world, loc.x, loc.y, loc.z)) {
            deferToExplosionClaim(item, itemKey, qty, groundHolder, epochMillis)
            return
        }

        val deltas = if (thrower != null) {
            listOf(InventoryDelta(HolderId.Player(thrower), itemKey, -qty), InventoryDelta(groundHolder, itemKey, qty))
        } else {
            listOf(InventoryDelta(groundHolder, itemKey, qty))
        }
        val cause = if (thrower != null) CauseKind.PLAYER_ACTION else CauseKind.WORLD
        val causedBy = thrower?.let(HolderId::Player)
        recordSpawn(deltas, epochMillis, cause, causedBy)
    }

    /**
     * Gives a nearby explosion release its window to claim this exact spawn first before falling
     * back to an ordinary unattributed mint for whatever it didn't claim.
     *
     * If the item was picked up (or otherwise removed) before this runs, there's nothing left
     * to credit either way — the same accepted "untracked material" gap as everywhere else, not
     * a new one.
     */
    private fun deferToExplosionClaim(item: Item, itemKey: ItemKey, qty: Long, groundHolder: HolderId.ItemEntity, epochMillis: Long) {
        Bukkit.getRegionScheduler().runDelayed(plugin, item.location, {
            if (!item.isValid) return@runDelayed
            val loc = item.location
            val claimed = services.explosionDrops.claim(loc.world, loc.x, loc.y, loc.z, itemKey, qty, groundHolder)
            val unclaimed = qty - claimed
            if (unclaimed > 0) {
                recordSpawn(listOf(InventoryDelta(groundHolder, itemKey, unclaimed)), epochMillis, CauseKind.WORLD, null)
            }
        }, EXPLOSION_CLAIM_DELAY_TICKS)
    }

    private fun recordSpawn(deltas: List<InventoryDelta>, epochMillis: Long, cause: CauseKind, causedBy: HolderId?) {
        services.scope.launch {
            try {
                withContext(services.schedulers.storage) {
                    services.capture.record(deltas, epochMillis, cause, causedBy)
                }
            } catch (e: IllegalStateException) {
                logger.log(Level.FINE, "untracked material dropped by $causedBy, not recorded", e)
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPickup(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        val item = event.item
        val itemKey = item.itemStack.toItemKey()
        val pickedUp = (item.itemStack.amount - event.remaining).toLong()
        if (pickedUp <= 0) return

        val groundHolder = HolderId.ItemEntity(item.uniqueId)
        val playerHolder = HolderId.Player(player.uniqueId)
        val epochMillis = System.currentTimeMillis()
        val deltas = listOf(InventoryDelta(groundHolder, itemKey, -pickedUp), InventoryDelta(playerHolder, itemKey, pickedUp))

        services.scope.launch {
            try {
                withContext(services.schedulers.storage) {
                    services.capture.record(deltas, epochMillis, CauseKind.PLAYER_ACTION, playerHolder)
                }
            } catch (e: IllegalStateException) {
                logger.log(Level.FINE, "untracked ground item picked up by $playerHolder, not recorded", e)
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDespawn(event: ItemDespawnEvent) {
        val groundHolder = HolderId.ItemEntity(event.entity.uniqueId)
        val epochMillis = System.currentTimeMillis()

        services.scope.launch {
            try {
                withContext(services.schedulers.storage) {
                    val believed = services.ledger.totalsAt(groundHolder)
                    if (believed.isEmpty()) return@withContext
                    val flows = believed.map { (itemKey, qty) ->
                        Flow(itemKey, qty, groundHolder, HolderId.Sink(SinkKind.DESPAWN), FlowKind.BURN)
                    }
                    services.capture.recordDirect(flows, epochMillis, CauseKind.WORLD, causedBy = null)
                }
            } catch (e: IllegalStateException) {
                logger.log(Level.FINE, "untracked ground item despawned at $groundHolder, not recorded", e)
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMerge(event: ItemMergeEvent) {
        val losingHolder = HolderId.ItemEntity(event.entity.uniqueId)
        val survivingHolder = HolderId.ItemEntity(event.target.uniqueId)
        val epochMillis = System.currentTimeMillis()

        services.scope.launch {
            try {
                withContext(services.schedulers.storage) {
                    val believed = services.ledger.totalsAt(losingHolder)
                    if (believed.isEmpty()) return@withContext
                    val flows = believed.map { (itemKey, qty) ->
                        Flow(itemKey, qty, losingHolder, survivingHolder, FlowKind.MOVE)
                    }
                    services.capture.recordDirect(flows, epochMillis, CauseKind.WORLD, causedBy = null)
                }
            } catch (e: IllegalStateException) {
                logger.log(Level.FINE, "untracked ground item merged from $losingHolder, not recorded", e)
            }
        }
    }

    private companion object {
        const val EXPLOSION_CLAIM_DELAY_TICKS = 4L
    }
}
