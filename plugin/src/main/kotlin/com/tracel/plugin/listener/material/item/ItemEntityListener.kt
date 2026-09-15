package com.tracel.plugin.listener.material.item

import com.tracel.annotations.CauseKind
import com.tracel.annotations.Observes
import com.tracel.annotations.Priority
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.item.ItemKey
import com.tracel.model.world.BlockPos
import com.tracel.plugin.TracelServices
import com.tracel.plugin.adapter.entity.kind.dropsManagedCargo
import com.tracel.plugin.adapter.entity.toBlockPos
import com.tracel.plugin.adapter.item.toHolderId
import com.tracel.plugin.adapter.item.toItemKey
import com.tracel.plugin.adapter.item.toItemTotals
import com.tracel.plugin.listener.TracelListener
import com.tracel.plugin.listener.support.BlockRelease
import com.tracel.plugin.listener.support.CREATIVE_SINK
import com.tracel.plugin.listener.support.CREATIVE_SOURCE
import com.tracel.plugin.listener.support.DESTROYED_SINK
import com.tracel.plugin.listener.support.harvestFlows
import com.tracel.plugin.listener.support.isLedgeredHolder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.bukkit.Bukkit
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDropItemEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.EntityRemoveEvent
import org.bukkit.event.entity.ItemDespawnEvent
import org.bukkit.event.entity.ItemMergeEvent
import org.bukkit.event.entity.ItemSpawnEvent
import org.bukkit.event.entity.PiglinBarterEvent
import org.bukkit.event.inventory.InventoryPickupItemEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.event.player.PlayerPickupArrowEvent

/**
 * Ground item entity listener: spawn, pickup, merge, despawn, and removals with no dedicated event
 * (blast, lava, cactus, void, `/kill`).
 */
class ItemEntityListener(services: TracelServices) : TracelListener(services) {
    private val pendingDrops = ConcurrentHashMap<UUID, HolderId>()

    @Observes
    fun onSpawn(event: ItemSpawnEvent) {
        if (services.selfManagedSpawns.isSelfManagedSpawn) return
        // Restore emptying a container dumps stacks as spawns; minting them dupes the ledger move
        if (restoring) return
        val item = event.entity
        val itemKey = item.itemStack.toItemKey()
        val qty = item.itemStack.amount.toLong()
        val groundHolder = HolderId.ItemEntity(item.uniqueId)
        val thrower = item.thrower
        val epochMillis = System.currentTimeMillis()
        val credited = pendingDrops.remove(item.uniqueId)
        if (credited != null) {
            creditDrop(credited, itemKey, qty, groundHolder, epochMillis, item.toBlockPos())
            return
        }
        val hull = services.hullDrops.take(item.location, itemKey)
        if (hull != null) {
            // Same PlacedEntity lot
            services.selfManagedSpawns.track(item.uniqueId)
            material.moved(
                cause = CauseKind.ENTITY_ACTION,
                causedBy = null,
                itemKey = itemKey,
                from = hull,
                to = groundHolder,
                quantity = qty,
                epochMillis = epochMillis
            )
            return
        }

        val loc = item.location
        if (thrower == null && services.blockDrops.hasNearbyRelease(loc.world, loc.x, loc.y, loc.z)) {
            // Track before the 4-tick claim. Merge in that window kills the correlator UUID
            services.selfManagedSpawns.track(item.uniqueId)
            deferToDropClaim(item, itemKey, qty, groundHolder, epochMillis)
            return
        }

        val throwerPlayer = thrower?.let { Bukkit.getPlayer(it) }
        if (thrower != null && throwerPlayer != null && !throwerPlayer.isLedgeredHolder()) {
            material.moved(
                cause = CauseKind.PLAYER_ACTION,
                causedBy = HolderId.Player(thrower),
                itemKey = itemKey,
                from = CREATIVE_SOURCE,
                to = groundHolder,
                quantity = qty,
                epochMillis = epochMillis
            )
            return
        }

        if (thrower != null) {
            val playerHolder = HolderId.Player(thrower)
            // Bypass of diff(); without adjust a later click compares against a stale pre-drop snapshot
            material.adjust(playerHolder, itemKey, -qty)
            material.moved(
                cause = CauseKind.PLAYER_ACTION,
                causedBy = playerHolder,
                itemKey = itemKey,
                from = playerHolder,
                to = groundHolder,
                quantity = qty,
                epochMillis = epochMillis
            )
        } else {
            material.single(CauseKind.WORLD, null, itemKey, groundHolder, qty, epochMillis)
        }
    }

    private fun deferToDropClaim(item: Item, itemKey: ItemKey, qty: Long, groundHolder: HolderId.ItemEntity, epochMillis: Long) {
        later(item.location, DROP_CLAIM_DELAY_TICKS) {
            if (!item.isValid) return@later
            val loc = item.location
            val claimed = services.blockDrops.claim(loc.world, loc.x, loc.y, loc.z, itemKey, qty, groundHolder)
            val unclaimed = qty - claimed
            if (unclaimed > 0) material.single(CauseKind.WORLD, null, itemKey, groundHolder, unclaimed, epochMillis)
        }
    }

    @Observes
    fun onPickup(event: EntityPickupItemEvent) {
        val item = event.item
        val tracked = services.selfManagedSpawns.isTracked(item.uniqueId)
        if (event.remaining <= 0) services.selfManagedSpawns.forget(item.uniqueId)

        // Undo of pickup needs the floor coordinate
        services.groundWhereabouts.remember(item)
        val player = event.entity as? Player
        if (player == null) {
            // Mob pickup
            val taken = (item.itemStack.amount - event.remaining).toLong()
            if (taken > 0) {
                material.moved(
                    cause = CauseKind.WORLD,
                    causedBy = null,
                    itemKey = item.itemStack.toItemKey(),
                    from = HolderId.ItemEntity(item.uniqueId),
                    to = HolderId.Entity(event.entity.uniqueId),
                    quantity = taken,
                )
            }
            return
        }
        val itemKey = item.itemStack.toItemKey()
        val pickedUp = (item.itemStack.amount - event.remaining).toLong()
        if (pickedUp <= 0) return

        val groundHolder = HolderId.ItemEntity(item.uniqueId)
        val playerHolder = HolderId.Player(player.uniqueId)

        if (!player.isLedgeredHolder() && !tracked) {
            material.moved(
                cause = CauseKind.PLAYER_ACTION,
                causedBy = playerHolder,
                itemKey = itemKey,
                from = groundHolder,
                to = CREATIVE_SINK,
                quantity = pickedUp
            )
            return
        }

        material.adjust(playerHolder, itemKey, pickedUp)
        material.moved(
            cause = CauseKind.PLAYER_ACTION,
            causedBy = playerHolder,
            itemKey = itemKey,
            from = groundHolder,
            to = playerHolder,
            quantity = pickedUp
        )
    }

    @Observes
    fun onDespawn(event: ItemDespawnEvent) {
        services.selfManagedSpawns.forget(event.entity.uniqueId)

        // Coordinate only needed when the pile is gone; dying is rarer than spawn
        services.groundWhereabouts.remember(event.entity)
        material.released(
            cause = CauseKind.WORLD,
            causedBy = null,
            from = HolderId.ItemEntity(event.entity.uniqueId),
            to = HolderId.Sink(SinkKind.DESPAWN),
        )
    }

    @Observes(priority = Priority.HIGH)
    fun onMergeCancel(event: ItemMergeEvent) {
        if (services.selfManagedSpawns.isTracked(event.entity.uniqueId) ||
            services.selfManagedSpawns.isTracked(event.target.uniqueId)
        ) {
            event.isCancelled = true
        }
    }

    @Observes
    fun onMerge(event: ItemMergeEvent) {
        services.selfManagedSpawns.forget(event.entity.uniqueId)
        services.groundWhereabouts.remember(event.entity)
        material.released(
            cause = CauseKind.WORLD,
            causedBy = null,
            from = HolderId.ItemEntity(event.entity.uniqueId),
            to = HolderId.ItemEntity(event.target.uniqueId),
        )
    }

    private companion object {
        const val DROP_CLAIM_DELAY_TICKS = 4L
    }

    @Observes(priority = Priority.HIGHEST)
    fun holdPickupWhileRestoring(event: InventoryPickupItemEvent) {
        val destination = event.inventory.toHolderId() ?: return
        if (services.frozen.isFrozen(destination) ||
            services.frozen.isFrozen(HolderId.ItemEntity(event.item.uniqueId))
        ) {
            event.isCancelled = true
        }
    }

    @Observes
    fun onHopperPickup(event: InventoryPickupItemEvent) {
        val destination = event.inventory.toHolderId() ?: return
        services.groundWhereabouts.remember(event.item)
        val stack = event.item.itemStack

        // Booked from the event, never diffed; unadjusted hopper re-reports the swallow
        material.adjust(destination, stack.toItemKey(), stack.amount.toLong())
        material.moved(
            cause = CauseKind.HOPPER,
            causedBy = null,
            itemKey = stack.toItemKey(),
            from = HolderId.ItemEntity(event.item.uniqueId),
            to = destination,
            quantity = stack.amount.toLong(),
        )
    }

    @Observes(priority = Priority.HIGH)
    fun onEntityDrop(event: EntityDropItemEvent) {
        if (event.entity is Player) return
        if (event.entity.dropsManagedCargo()) return
        pendingDrops[event.itemDrop.uniqueId] = HolderId.Entity(event.entity.uniqueId)
    }

    @Observes
    fun onFish(event: PlayerFishEvent) {
        if (event.state != PlayerFishEvent.State.CAUGHT_FISH) return
        val player = event.player
        material.scheduleReconcile(player)
    }

    @Observes
    fun onBarter(event: PiglinBarterEvent) {
        val piglin = event.entity
        val holder = HolderId.Entity(piglin.uniqueId)
        val at = piglin.location
        val input = event.input
        if (!input.type.isAir && input.amount > 0) {
            material.adjust(holder, input.toItemKey(), -input.amount.toLong())
            material.moved(
                cause = CauseKind.WORLD,
                causedBy = null,
                itemKey = input.toItemKey(),
                from = holder,
                to = DESTROYED_SINK,
                quantity = input.amount.toLong()
            )
        }
        val outcome = event.outcome.toItemTotals()
        if (outcome.isEmpty()) return
        material.releasing(
            releases = listOf(BlockRelease(holder, at.world, at.blockX, at.blockY, at.blockZ, outcome)),
            cause = CauseKind.WORLD,
            causedBy = null,
            at = piglin.toBlockPos(),
        )
    }

    @Observes
    fun onPickupArrow(event: PlayerPickupArrowEvent) {
        val player = event.player
        material.scheduleReconcile(player)
    }

    @Observes(ignoreCancelled = false)
    fun onRemove(event: EntityRemoveEvent) {
        val item = event.entity as? Item ?: return
        if (restoring) return
        val sink = sinkFor(event.cause) ?: return
        material.released(
            cause = CauseKind.WORLD,
            causedBy = null,
            from = HolderId.ItemEntity(item.uniqueId),
            to = HolderId.Sink(sink)
        )
    }

    // TODO: improve this
    private fun sinkFor(cause: EntityRemoveEvent.Cause): SinkKind? = when (cause) {
        EntityRemoveEvent.Cause.EXPLODE,
        EntityRemoveEvent.Cause.HIT,
        EntityRemoveEvent.Cause.ENTER_BLOCK,
        EntityRemoveEvent.Cause.OUT_OF_WORLD,
        EntityRemoveEvent.Cause.DISCARD,
        EntityRemoveEvent.Cause.DEATH,
        -> SinkKind.UNATTRIBUTED

        EntityRemoveEvent.Cause.DESPAWN,
        EntityRemoveEvent.Cause.PLUGIN,
        EntityRemoveEvent.Cause.PICKUP,
        EntityRemoveEvent.Cause.MERGE,
        EntityRemoveEvent.Cause.UNLOAD,
        EntityRemoveEvent.Cause.PLAYER_QUIT,
        EntityRemoveEvent.Cause.TRANSFORMATION,
        EntityRemoveEvent.Cause.DROP,
        -> null
    }

    private fun creditDrop(
        from: HolderId,
        itemKey: ItemKey,
        qty: Long,
        ground: HolderId,
        epochMillis: Long,
        at: BlockPos,
    ) = material.direct(
        flows = harvestFlows(mapOf(itemKey to qty), from, ground),
        cause = if (from is HolderId.Player) CauseKind.PLAYER_ACTION else CauseKind.ENTITY_ACTION,
        causedBy = from,
        at = at,
        epochMillis = epochMillis,
    )
}
