package com.tracel.plugin.listener.material.item

import com.tracel.model.transaction.CauseKind
import com.tracel.annotations.Observes
import com.tracel.annotations.Priority
import com.tracel.engine.balance.InventoryDelta
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.id.WorldId
import com.tracel.model.item.ItemKey
import com.tracel.model.world.BlockPos
import com.tracel.plugin.TracelServices
import com.tracel.plugin.adapter.entity.kind.dropsManagedCargo
import com.tracel.plugin.adapter.entity.toBlockPos
import com.tracel.plugin.adapter.entity.toCargoHolderId
import com.tracel.plugin.adapter.item.toHolderId
import com.tracel.plugin.adapter.item.toItemKey
import com.tracel.plugin.adapter.item.toItemTotals
import com.tracel.plugin.adapter.item.totalsOf
import com.tracel.plugin.listener.TracelListener
import com.tracel.plugin.listener.support.drop.BlockRelease
import com.tracel.plugin.listener.support.drop.ContainerDrop
import com.tracel.plugin.listener.support.drop.CraftDrop
import com.tracel.plugin.listener.support.entity.HitActor
import com.tracel.plugin.listener.support.entity.damageBlame
import com.tracel.plugin.listener.support.flow.CREATIVE_SINK
import com.tracel.plugin.listener.support.flow.CREATIVE_SOURCE
import com.tracel.plugin.listener.support.flow.DESTROYED_SINK
import com.tracel.plugin.listener.support.flow.isLedgeredHolder
import com.tracel.plugin.util.ExpiringMap
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Item
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockShearEntityEvent
import org.bukkit.event.entity.*
import org.bukkit.event.inventory.InventoryPickupItemEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.event.player.PlayerPickupArrowEvent
import org.bukkit.event.player.PlayerShearEntityEvent
import org.bukkit.inventory.ItemStack
import java.util.*
import java.util.concurrent.atomic.AtomicLong

/**
 * Ground item entity listener: spawn, pickup, merge, despawn, and removals with no dedicated event
 * (blast, lava, cactus, void, `/kill`).
 */
class ItemEntityListener(services: TracelServices) : TracelListener(services) {
    private val pendingDrops = ExpiringMap<UUID, HolderId>(PENDING_DROP_MS)
    private val dropBlame = ExpiringMap<UUID, HolderId>(PENDING_DROP_MS)
    private val sheared = ExpiringMap<UUID, Unit>(SHEAR_DROP_MS)

    private data class FramePop(
        val world: UUID,
        val at: Location,
        val holder: HolderId,
        val key: ItemKey,
        val by: HolderId?
    )

    private val framePops = ExpiringMap<Long, FramePop>(SHEAR_DROP_MS)
    private val nextPop = AtomicLong()

    private companion object {
        const val DROP_CLAIM_DELAY_TICKS = 4L
        const val SHEAR_DROP_MS = 1_000L
        const val PENDING_DROP_MS = 5_000L
        const val FRAME_POP_RADIUS = 1.5
    }

    @Observes
    fun onShear(event: PlayerShearEntityEvent) {
        sheared.put(event.entity.uniqueId, Unit)
    }

    @Observes
    fun onDispenserShear(event: BlockShearEntityEvent) {
        sheared.put(event.entity.uniqueId, Unit)
    }

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
            creditDrop(
                credited,
                itemKey,
                qty,
                groundHolder,
                epochMillis,
                item.location,
                dropBlame.remove(item.uniqueId) ?: credited
            )
            return
        }
        val popped = takeFramePop(item.location, itemKey)
        if (popped != null) {
            material.adjust(popped.holder, itemKey, -qty)
            creditDrop(
                popped.holder,
                itemKey,
                qty,
                groundHolder,
                epochMillis,
                item.location,
                popped.by ?: popped.holder
            )
            return
        }
        val hull = services.hullDrops.take(item.location, itemKey)
        if (hull != null) {
            // Same PlacedEntity lot
            services.selfManagedSpawns.track(item.uniqueId)
            material.positioned(
                cause = if (hull.by is HolderId.Player) CauseKind.PLAYER_ACTION else CauseKind.ENTITY_ACTION,
                causedBy = hull.by,
                at = item.location,
                deltas = listOf(InventoryDelta(hull.holder, itemKey, -qty), InventoryDelta(groundHolder, itemKey, qty)),
                epochMillis = epochMillis,
                mintShortfallAt = hull.holder,
            )
            return
        }

        val loc = item.location
        val window = if (thrower == null) services.blockDrops.nearestToken(loc.world, loc.x, loc.y, loc.z) else null
        if (window != null) {
            // Track before the 4-tick claim. Merge in that window kills the correlator UUID
            services.selfManagedSpawns.track(item.uniqueId)
            services.blockReleases.hold(groundHolder, window)
            deferToDropClaim(item, loc.clone(), itemKey, qty, groundHolder, epochMillis, window)
            return
        }

        val throwerPlayer = thrower?.let { Bukkit.getPlayer(it) }
        val outOf = thrower?.let { ContainerDrop.take(it, itemKey) }
        if (thrower != null && outOf != null) {
            material.adjust(outOf, itemKey, -qty)
            material.positioned(
                cause = CauseKind.PLAYER_ACTION,
                causedBy = HolderId.Player(thrower),
                at = item.location,
                deltas = listOf(InventoryDelta(outOf, itemKey, -qty), InventoryDelta(groundHolder, itemKey, qty)),
                epochMillis = epochMillis,
                mintShortfallAt = outOf,
            )
            return
        }
        if (thrower != null && CraftDrop.expecting(thrower)) {
            CraftDrop.add(thrower, CraftDrop.Thrown(groundHolder, itemKey, qty))
            return
        }

        val stack = item.itemStack
        if (thrower != null && throwerPlayer != null && !throwerPlayer.isLedgeredHolder()) {
            movedStack(
                CauseKind.PLAYER_ACTION,
                HolderId.Player(thrower),
                stack,
                qty,
                CREATIVE_SOURCE,
                groundHolder,
                epochMillis,
                item.location
            )
            return
        }

        if (thrower != null) {
            val playerHolder = HolderId.Player(thrower)
            // Bypass of diff(); without adjust a later click compares against a stale pre-drop snapshot
            for ((key, amount) in stack.totalsOf(qty)) material.adjust(playerHolder, key, -amount)
            movedStack(
                CauseKind.PLAYER_ACTION,
                playerHolder,
                stack,
                qty,
                playerHolder,
                groundHolder,
                epochMillis,
                item.location
            )
        } else {
            for ((key, amount) in stack.totalsOf(qty)) material.single(
                CauseKind.WORLD,
                null,
                key,
                groundHolder,
                amount,
                epochMillis
            )
        }
    }

    private fun deferToDropClaim(
        item: Item,
        spawnedAt: Location,
        itemKey: ItemKey,
        qty: Long,
        groundHolder: HolderId.ItemEntity,
        epochMillis: Long,
        window: Long,
    ) {
        later(spawnedAt, DROP_CLAIM_DELAY_TICKS) {
            try {
                val loc = spawnedAt
                val claimed = services.blockDrops.claim(loc.world, loc.x, loc.y, loc.z, itemKey, qty, groundHolder)
                val unclaimed = qty - claimed
                if (unclaimed > 0) material.single(CauseKind.WORLD, null, itemKey, groundHolder, unclaimed, epochMillis)
            } finally {
                services.blockReleases.claimed(window)
            }
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
                movedStack(
                    CauseKind.WORLD,
                    null,
                    item.itemStack,
                    taken,
                    HolderId.ItemEntity(item.uniqueId),
                    HolderId.Entity(event.entity.uniqueId)
                )
            }
            return
        }
        val pickedUp = (item.itemStack.amount - event.remaining).toLong()
        if (pickedUp <= 0) return

        val groundHolder = HolderId.ItemEntity(item.uniqueId)
        val playerHolder = HolderId.Player(player.uniqueId)

        if (!player.isLedgeredHolder() && !tracked) {
            movedStack(
                CauseKind.PLAYER_ACTION,
                playerHolder,
                item.itemStack,
                pickedUp,
                groundHolder,
                CREATIVE_SINK,
                at = item.location
            )
            return
        }

        for ((key, amount) in item.itemStack.totalsOf(pickedUp)) material.adjust(playerHolder, key, amount)
        movedStack(
            CauseKind.PLAYER_ACTION,
            playerHolder,
            item.itemStack,
            pickedUp,
            groundHolder,
            playerHolder,
            at = item.location
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
        val item = event.item
        services.groundWhereabouts.remember(item)
        val stack = item.itemStack.clone()
        val before = stack.amount.toLong()
        val at = event.inventory.location ?: item.location

        later(at) {
            val remaining = if (item.isValid) item.itemStack.amount.toLong() else 0L
            val taken = before - remaining
            if (taken <= 0L) return@later
            for ((key, amount) in stack.totalsOf(taken)) material.adjust(destination, key, amount)
            movedStack(CauseKind.HOPPER, null, stack, taken, HolderId.ItemEntity(item.uniqueId), destination)
        }
    }

    @Observes(priority = Priority.HIGH)
    fun onEntityDrop(event: EntityDropItemEvent) {
        val entity = event.entity
        if (entity is Player) return
        if (entity is ItemFrame && entity.isValid) {
            val holder = entity.toCargoHolderId()
            val stack = event.itemDrop.itemStack
            material.adjust(holder, stack.toItemKey(), -stack.amount.toLong())
            pendingDrops.put(event.itemDrop.uniqueId, holder)
            HitActor.of(entity)?.let { dropBlame.put(event.itemDrop.uniqueId, it) }
            return
        }
        if (entity.dropsManagedCargo()) return
        if (entity.uniqueId in sheared) return
        pendingDrops.put(event.itemDrop.uniqueId, HolderId.Entity(event.entity.uniqueId))
    }

    @Observes
    fun onFrameHit(event: EntityDamageEvent) {
        val frame = event.entity as? ItemFrame ?: return
        if (frame.isFixed || frame.isInvulnerable) return
        val stack = frame.item
        if (stack.type.isAir) return
        val world = frame.world.uid
        framePops.put(
            nextPop.incrementAndGet(),
            FramePop(world, frame.location, frame.toCargoHolderId(), stack.toItemKey(), services.damageBlame(event).who)
        )
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

        // Gone, merged, picked up by a hopper or unloaded: the tracked set only ever grew
        if (event.cause != EntityRemoveEvent.Cause.UNLOAD) services.selfManagedSpawns.forget(item.uniqueId)
        if (restoring) return
        val sink = sinkFor(event.cause) ?: return
        material.released(
            cause = CauseKind.WORLD,
            causedBy = null,
            from = HolderId.ItemEntity(item.uniqueId),
            to = HolderId.Sink(sink)
        )
    }

    private fun takeFramePop(at: Location, key: ItemKey): FramePop? {
        val world = at.world?.uid ?: return null
        var best: Pair<Long, FramePop>? = null
        var bestDist = FRAME_POP_RADIUS * FRAME_POP_RADIUS
        framePops.forEachFresh { id, pop ->
            if (pop.world != world || pop.key != key) return@forEachFresh
            val d = pop.at.distanceSquared(at)
            if (d <= bestDist) {
                bestDist = d
                best = id to pop
            }
        }
        return best?.let { (id, pop) -> framePops.remove(id); pop }
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

    private fun movedStack(
        cause: CauseKind,
        causedBy: HolderId?,
        stack: ItemStack,
        quantity: Long,
        from: HolderId,
        to: HolderId,
        epochMillis: Long = System.currentTimeMillis(),
        at: Location? = null,
    ) {
        val where = at?.takeIf { causedBy is HolderId.Player }
            ?.let { BlockPos(WorldId(it.world.uid), it.blockX, it.blockY, it.blockZ) }
        for ((key, amount) in stack.totalsOf(quantity)) {
            material.moved(
                cause = cause,
                causedBy = causedBy,
                itemKey = key,
                from = from,
                to = to,
                quantity = amount,
                epochMillis = epochMillis,
                at = where
            )
        }
    }

    private fun creditDrop(
        from: HolderId,
        itemKey: ItemKey,
        qty: Long,
        ground: HolderId,
        epochMillis: Long,
        at: Location,
        causedBy: HolderId = from,
    ) = material.positioned(
        cause = if (causedBy is HolderId.Player) CauseKind.PLAYER_ACTION else CauseKind.ENTITY_ACTION,
        causedBy = causedBy,
        at = at,
        deltas = listOf(InventoryDelta(from, itemKey, -qty), InventoryDelta(ground, itemKey, qty)),
        epochMillis = epochMillis,
        mintShortfallAt = from,
    )
}
