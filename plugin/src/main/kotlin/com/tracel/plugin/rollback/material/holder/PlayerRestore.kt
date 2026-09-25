package com.tracel.plugin.rollback.material.holder

import com.tracel.annotations.Unstable
import org.bukkit.Bukkit
import kotlinx.coroutines.launch
import java.util.UUID
import com.tracel.plugin.rollback.material.deliverPending
import com.tracel.model.holder.HolderId
import com.tracel.model.id.RollbackJobId
import com.tracel.model.item.ItemKey
import com.tracel.plugin.adapter.item.toItemKey
import com.tracel.plugin.adapter.item.toItemTotals
import com.tracel.plugin.adapter.item.heldTotals
import com.tracel.plugin.adapter.item.openGrid
import com.tracel.plugin.adapter.world.playerOf
import com.tracel.plugin.rollback.material.ApplyResult
import com.tracel.plugin.rollback.material.MaterialRestorer
import com.tracel.plugin.rollback.material.item.Moves
import com.tracel.plugin.rollback.material.item.WornStacks
import com.tracel.plugin.rollback.material.item.applyDelta
import com.tracel.plugin.rollback.material.spill.Spill
import com.tracel.plugin.rollback.material.spill.spillInRegion
import kotlinx.coroutines.withContext
import com.tracel.plugin.rollback.material.item.matches
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot

@Unstable
private const val STORAGE_SLOTS = 36

/** Online: apply inventory. Offline: pending queue. */
@Unstable
internal suspend fun MaterialRestorer.applyToPlayer(
    holder: HolderId.Player,
    deltas: Map<ItemKey, Long>,
    forms: Map<ItemKey, ByteArray>,
    job: RollbackJobId,
    sink: MutableCollection<Spill>,
    worn: WornStacks? = null,
): ApplyResult =
    withContext(services.schedulers.entity(holder.uuid)) {
        val player = playerOf(holder.uuid)
        if (player == null || player.isDead) {
            services.atomically {
                services.pendingDeliveries.enqueueAll(holder.uuid, deltas, job, System.currentTimeMillis())
            }
            deliverIfBack(holder.uuid)
            return@withContext ApplyResult.Queued("player is offline - ${deltas.size} item key(s) queued for delivery on next login")
        }
        val moves = Moves()
        for ((itemKey, delta) in deltas) {
            applyDelta(itemKey, delta, forms[itemKey], moves, player.inventory, worn = worn)
        }
        takeFromCursor(player, moves)
        takeFromGrid(player, moves)
        wearGiven(player, deltas)
        val at = player.location
        spillInRegion(holder, moves, at.world, at, sink)
        services.differ.rebaseline(holder, player.heldTotals())
        moves.reason?.let(ApplyResult::Failed) ?: ApplyResult.Ok
    }

/** Apply per-player ender inventory. */
internal suspend fun MaterialRestorer.applyToEnderChest(
    holder: HolderId.EnderChest,
    deltas: Map<ItemKey, Long>,
    forms: Map<ItemKey, ByteArray>,
    job: RollbackJobId,
    sink: MutableCollection<Spill>,
    worn: WornStacks? = null,
): ApplyResult =
    withContext(services.schedulers.entity(holder.uuid)) {
        val player = playerOf(holder.uuid)
        if (player == null) {
            services.atomically {
                services.pendingDeliveries.enqueueAll(holder.uuid, deltas, job, System.currentTimeMillis(), enderChest = true)
            }
            deliverIfBack(holder.uuid)
            return@withContext ApplyResult.Queued(
                "player is offline - ${deltas.size} item key(s) queued for delivery to the ender chest on next login",
            )
        }
        val moves = Moves()
        val enderChest = player.enderChest
        for ((itemKey, delta) in deltas) {
            applyDelta(itemKey, delta, forms[itemKey], moves, enderChest, worn = worn)
        }
        val at = player.location
        spillInRegion(holder, moves, at.world, at, sink)
        services.differ.rebaseline(holder, enderChest.toItemTotals())
        moves.reason?.let(ApplyResult::Failed) ?: ApplyResult.Ok
    }

/** Take remaining [Moves.owed] from the cursor. */
internal fun MaterialRestorer.takeFromCursor(player: Player, moves: Moves) {
    if (moves.shortfalls <= 0L) return
    val cursor = player.itemOnCursor
    if (cursor.isEmpty || cursor.type.isAir) return
    val key = cursor.toItemKey()
    val owed = moves.owed(key)
    if (owed <= 0L) return
    val take = minOf(owed, cursor.amount.toLong()).toInt()
    if (take <= 0) return
    if (take >= cursor.amount) player.setItemOnCursor(null)
    else player.setItemOnCursor(cursor.clone().apply { amount -= take })
    moves.forgive(key, take.toLong())
}

/** Take what is still owed from the crafting grid the player has open. */
internal fun takeFromGrid(player: Player, moves: Moves) {
    if (moves.shortfalls <= 0L) return
    val grid = player.openGrid() ?: return
    val matrix = grid.matrix
    var changed = false
    for (i in matrix.indices) {
        val stack = matrix[i] ?: continue
        if (stack.isEmpty || stack.type.isAir) continue
        val key = stack.toItemKey()
        val owed = moves.owed(key)
        if (owed <= 0L) continue
        val take = minOf(owed, stack.amount.toLong()).toInt()
        matrix[i] = if (take >= stack.amount) null else stack.clone().apply { amount -= take }
        moves.forgive(key, take.toLong())
        changed = true
    }
    if (changed) grid.matrix = matrix
}

/**
 * Armor and a shield given back go on the body when that slot is free: a looted corpse got its helmet
 * back in the hotbar. Moved from where the give put it, so the worn stack keeps its wear.
 */
internal fun wearGiven(player: Player, deltas: Map<ItemKey, Long>) {
    val inventory = player.inventory
    for ((itemKey, delta) in deltas) {
        if (delta <= 0L) continue
        val slot = runCatching { Material.valueOf(itemKey.material).equipmentSlot }.getOrNull() ?: continue
        if (slot == EquipmentSlot.HAND || slot == EquipmentSlot.BODY || slot == EquipmentSlot.SADDLE) continue
        val worn = inventory.getItem(slot)
        if (!worn.isEmpty && !worn.type.isAir) continue
        for (index in 0 until STORAGE_SLOTS) {
            val stack = inventory.getItem(index) ?: continue
            if (stack.isEmpty || stack.maxStackSize != 1 || !stack.matches(itemKey)) continue
            inventory.setItem(slot, stack.clone().apply { amount = 1 })
            inventory.setItem(index, if (stack.amount > 1) stack.clone().apply { amount -= 1 } else null)
            break
        }
    }
}

private fun MaterialRestorer.deliverIfBack(uuid: UUID) {
    val player = Bukkit.getPlayer(uuid) ?: return
    if (!player.isOnline || player.isDead) return
    services.scope.launch { deliverPending(player) }
}
