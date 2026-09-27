package com.tracel.plugin.listener.material.item

import com.tracel.plugin.adapter.entity.toBlockPos
import com.tracel.plugin.listener.support.entity.HitActor
import com.tracel.annotations.CauseKind
import com.tracel.annotations.Observes
import com.tracel.annotations.Unstable
import com.tracel.engine.balance.InventoryDelta
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.item.ItemKey
import com.tracel.plugin.TracelServices
import com.tracel.plugin.adapter.entity.cargoStacks
import com.tracel.plugin.adapter.entity.kind.dropsSelf
import com.tracel.plugin.adapter.entity.emptyCargo
import com.tracel.plugin.adapter.entity.kind.hullItemKey
import com.tracel.plugin.adapter.entity.toCargoHolderId
import com.tracel.plugin.adapter.item.toItemKey
import com.tracel.plugin.adapter.item.toItemTotals
import com.tracel.plugin.adapter.entity.toPlacedEntityId
import com.tracel.plugin.listener.TracelListener
import com.tracel.plugin.listener.support.flow.isLedgeredHolder
import com.tracel.plugin.util.ExpiringMap
import com.tracel.plugin.listener.world.entity.isCommand
import org.bukkit.Bukkit
import org.bukkit.entity.AbstractVillager
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.ChestedHorse
import org.bukkit.entity.Mob
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityPlaceEvent
import org.bukkit.event.hanging.HangingBreakByEntityEvent
import org.bukkit.event.hanging.HangingBreakEvent
import org.bukkit.event.hanging.HangingPlaceEvent
import org.bukkit.event.player.PlayerArmorStandManipulateEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.vehicle.VehicleCreateEvent
import org.bukkit.event.vehicle.VehicleDestroyEvent
import org.bukkit.inventory.InventoryHolder
import java.util.UUID

/**
 * Entity cargo listener.
 *
 * Unstable because of strange tick handling.
 */
@Unstable
class EntityCargoListener(services: TracelServices) : TracelListener(services) {
    private val byPlayer = ExpiringMap<UUID, Unit>(RECENT_MS)

    @Observes
    fun onPlace(event: EntityPlaceEvent) {
        val player = event.player
        if (player != null) {
            byPlayer.put(event.entity.uniqueId, Unit)
            // the hand it came from: a boat placed from the off hand is not the sword in the main hand
            placed(event.entity, player, player.inventory.getItem(event.hand).toItemKey())
            return
        }
        dispensed(event.entity)
    }

    @Observes
    fun onVehicleCreate(event: VehicleCreateEvent) {
        val vehicle = event.vehicle
        if (byPlayer.remove(vehicle.uniqueId) != null) return
        dispensed(vehicle)
    }

    private fun dispensed(entity: Entity) {
        if (restoring) return
        if (!entity.dropsSelf()) return
        val itemKey = entity.hullItemKey() ?: return
        val holder = entity.toPlacedEntityId()
        later(entity.location, DISPENSE_CLAIM_DELAY_TICKS) {
            if (!entity.isValid) return@later
            val at = entity.location
            // Empty claim means no nearby release; minting there doubled a dispensed arrow
            services.blockDrops.claim(at.world, at.x, at.y, at.z, itemKey, 1L, holder)
        }
    }

    @Observes
    fun onHangingPlace(event: HangingPlaceEvent) {
        val player = event.player ?: return
        placed(event.entity, player, event.itemStack?.toItemKey() ?: return)
    }

    private fun placed(entity: Entity, player: Player, itemKey: ItemKey) {
        val playerHolder = HolderId.Player(player.uniqueId)
        val ledgered = player.isLedgeredHolder()
        if (ledgered) material.adjust(
            holder = playerHolder,
            itemKey = itemKey,
            delta = -1L
        )
        val deltas = buildList {
            add(InventoryDelta(entity.toPlacedEntityId(), itemKey, 1L))
            if (ledgered) add(InventoryDelta(playerHolder, itemKey, -1L))
        }
        material.positioned(
            cause = CauseKind.PLAYER_ACTION,
            causedBy = playerHolder,
            at = entity.location,
            deltas = deltas
        )
    }

    @Observes
    fun onVehicleDestroy(event: VehicleDestroyEvent) {
        destroyed(event.vehicle, (event.attacker as? Player)?.let { HolderId.Player(it.uniqueId) } ?: HitActor.of(event.vehicle))
    }

    @Observes
    fun onDeath(event: EntityDeathEvent) {
        // PlayerDeathEvent is EntityDeathEvent and Player is InventoryHolder.
        // Treating pockets as hull cargo will dupe drops.
        // DeathDropListener.onPlayerDeath owns player death.
        if (event.entity is Player) return

        withholdManagedCargo(event)
        destroyed(event.entity, event.entity.killer?.let { HolderId.Player(it.uniqueId) } ?: HitActor.of(event.entity))
    }

    private fun withholdManagedCargo(event: EntityDeathEvent) {
        val cargo = event.entity.cargoStacks()
        if (cargo.isEmpty()) return

        val owed = HashMap<ItemKey, Long>()
        for (stack in cargo) owed.merge(stack.toItemKey(), stack.amount.toLong(), Long::plus)

        val drops = event.drops.iterator()
        while (drops.hasNext()) {
            val stack = drops.next()
            val left = owed[stack.toItemKey()] ?: continue
            if (left <= 0L) continue
            if (stack.amount <= left) {
                owed[stack.toItemKey()] = left - stack.amount
                drops.remove()
            } else {
                stack.amount -= left.toInt()
                owed[stack.toItemKey()] = 0L
            }
        }
    }

    @Observes
    fun onHangingBreak(event: HangingBreakEvent) {
        // HangingBreakByEntityEvent extends HangingBreakEvent; handling both released the painting twice
        val by = (event as? HangingBreakByEntityEvent)?.remover as? Player
        val who = by?.let { HolderId.Player(it.uniqueId) }
            ?: services.redstoneTriggers.recentExplosionNear(event.entity.location)
        destroyed(event.entity, who)
    }

    private fun destroyed(entity: Entity, causedBy: HolderId?) {
        // Restore must not log a destruction it is about to undo
        if (restoring) return
        val epochMillis = System.currentTimeMillis()

        val hull = if (entity.dropsSelf()) entity.hullItemKey() else null
        if (hull != null) {
            services.hullDrops.expect(entity.location, entity.toPlacedEntityId(), hull, causedBy)
        } else {
            material.released(
                cause = CauseKind.ENTITY_ACTION,
                causedBy = causedBy,
                from = entity.toPlacedEntityId(),
                to = HolderId.Sink(SinkKind.UNATTRIBUTED),
                epochMillis = epochMillis,
            )
        }

        if (entity is AbstractVillager) {
            material.released(
                cause = CauseKind.ENTITY_ACTION,
                causedBy = causedBy,
                from = entity.toCargoHolderId(),
                to = HolderId.Sink(SinkKind.UNATTRIBUTED),
                epochMillis = epochMillis,
            )
            return
        }
        val cargo = entity is InventoryHolder || entity is ItemFrame || entity is ArmorStand
        if (!cargo) return
        dropCargo(entity, causedBy, epochMillis)
    }

    private fun dropCargo(entity: Entity, causedBy: HolderId?, epochMillis: Long) {
        // Read before empty. Respawning only ledgered lots made pre-plugin frames drop nothing
        val removed = entity.cargoStacks()
        entity.emptyCargo()
        material.dropping(
            holder = entity.toCargoHolderId(),
            at = entity.location,
            cause = CauseKind.ENTITY_ACTION,
            causedBy = causedBy,
            epochMillis = epochMillis,
            removed = removed
        )
    }

    @Observes
    fun onKillCommand(event: PlayerCommandPreprocessEvent) {
        val body = event.message.removePrefix("/")
        if (!body.isCommand("kill")) return
        if (!event.player.hasPermission("minecraft.command.kill")) return
        val selector = body.substringAfter(' ', "").trim().ifEmpty { return }
        val targets = runCatching { Bukkit.selectEntities(event.player, selector) }.getOrNull() ?: return
        val by = HolderId.Player(event.player.uniqueId)
        val epochMillis = System.currentTimeMillis()
        for (target in targets) {
            if (target is LivingEntity || target !is InventoryHolder) continue
            if (!Bukkit.isOwnedByCurrentRegion(target)) continue
            if (restoring) return
            dropCargo(target, by, epochMillis)
        }
    }

    // No seed: SnapshotDiffer already marks unseen holders as gaps.
    // Rebaseline would hide that drift.

    @Observes
    fun onFrameInteract(event: PlayerInteractEntityEvent) {
        val frame = event.rightClicked as? ItemFrame ?: return
        cargoChanged(event.player, frame) { listOf(frame.item).toItemTotals() }
    }

    @Observes
    fun onEquipMob(event: PlayerInteractEntityEvent) {
        val mob = event.rightClicked
        if (mob !is ChestedHorse && mob !is Mob) return
        if (mob is ArmorStand) return
        cargoChanged(event.player, mob) { mob.cargoStacks().toItemTotals() }
    }

    @Observes
    fun onArmorStand(event: PlayerArmorStandManipulateEvent) {
        val stand = event.rightClicked
        cargoChanged(event.player, stand) { stand.cargoStacks().toItemTotals() }
    }

    private fun cargoChanged(player: Player, entity: Entity, totals: () -> Map<ItemKey, Long>) {
        material.seedOnOpen(entity.toCargoHolderId(), totals(), entity.toBlockPos())
        later(entity) {
            val cargo = totals()
            val holder = entity.toCargoHolderId()
            later(player) { material.reconcile(
                inventories = listOf(player.inventory),
                player = player,
                extra = mapOf(holder to cargo)
            ) }
        }
    }

    private companion object {
        const val DISPENSE_CLAIM_DELAY_TICKS = 4L
        const val RECENT_MS = 5_000L
    }
}
