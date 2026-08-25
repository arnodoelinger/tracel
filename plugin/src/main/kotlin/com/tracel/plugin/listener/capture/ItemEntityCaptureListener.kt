package com.tracel.plugin.listener.capture

import com.tracel.annotations.CauseKind
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.item.ItemKey
import com.tracel.plugin.TracelServices
import com.tracel.plugin.convert.toItemKey
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

/**
 * Captures ground items — a `Bukkit` `Item` entity is the one holder in this codebase whose
 * exact contents are always known directly from the event that touches it.
 */
class ItemEntityCaptureListener(
    private val services: TracelServices,
    private val plugin: Plugin,
) : Listener {
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

        if (thrower != null) {
            val playerHolder = HolderId.Player(thrower)
            // Keeps cached snapshot for the player honest. This capture bypasses diff()
            // entirely, so without this a later click / close diff for the same holder would
            // compare live state against a stale pre-drop snapshot.
            services.differ.adjust(playerHolder, itemKey, -qty)
            services.gate.move(CauseKind.PLAYER_ACTION, playerHolder, epochMillis, itemKey, playerHolder, groundHolder, qty)
        } else {
            services.gate.single(CauseKind.WORLD, null, epochMillis, itemKey, groundHolder, qty)
        }
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
                services.gate.single(CauseKind.WORLD, null, epochMillis, itemKey, groundHolder, unclaimed)
            }
        }, EXPLOSION_CLAIM_DELAY_TICKS)
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

        services.differ.adjust(playerHolder, itemKey, pickedUp)
        services.gate.move(
            CauseKind.PLAYER_ACTION,
            playerHolder,
            System.currentTimeMillis(),
            itemKey,
            groundHolder,
            playerHolder,
            pickedUp,
        )
    }

    /**
     * A despawn and a merge are the same shape: this holder is gone, everything it had is now
     * somewhere else. Naming the pair is all a region thread can honestly do — what the entity
     * held is a ledger read, resolved on the storage thread when the ring is drained.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDespawn(event: ItemDespawnEvent) {
        services.gate.release(
            CauseKind.WORLD,
            causedBy = null,
            epochMillis = System.currentTimeMillis(),
            from = HolderId.ItemEntity(event.entity.uniqueId),
            to = HolderId.Sink(SinkKind.DESPAWN),
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMerge(event: ItemMergeEvent) {
        services.gate.release(
            CauseKind.WORLD,
            causedBy = null,
            epochMillis = System.currentTimeMillis(),
            from = HolderId.ItemEntity(event.entity.uniqueId),
            to = HolderId.ItemEntity(event.target.uniqueId),
        )
    }

    private companion object {
        const val EXPLOSION_CLAIM_DELAY_TICKS = 4L
    }
}
