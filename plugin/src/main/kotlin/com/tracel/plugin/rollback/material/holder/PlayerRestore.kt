package com.tracel.plugin.rollback.material.holder

import com.tracel.model.holder.HolderId
import com.tracel.model.id.RollbackJobId
import com.tracel.model.item.ItemKey
import com.tracel.plugin.adapter.item.toItemKey
import com.tracel.plugin.adapter.item.toItemTotals
import com.tracel.plugin.adapter.item.withCursor
import com.tracel.plugin.adapter.world.playerOf
import com.tracel.plugin.rollback.material.ApplyResult
import com.tracel.plugin.rollback.material.MaterialRestorer
import com.tracel.plugin.rollback.material.item.Moves
import com.tracel.plugin.rollback.material.item.applyDelta
import com.tracel.plugin.rollback.material.spill.Spill
import com.tracel.plugin.rollback.material.spill.spillInRegion
import kotlinx.coroutines.withContext
import org.bukkit.entity.Player

/** Online: apply inventory. Offline: pending queue. */
internal suspend fun MaterialRestorer.applyToPlayer(
    holder: HolderId.Player,
    deltas: Map<ItemKey, Long>,
    forms: Map<ItemKey, ByteArray>,
    job: RollbackJobId,
    sink: MutableCollection<Spill>,
): ApplyResult =
    withContext(services.schedulers.entity(holder.uuid)) {
        val player = playerOf(holder.uuid)
        if (player == null) {
            services.atomically {
                services.pendingDeliveries.enqueueAll(holder.uuid, deltas, job, System.currentTimeMillis())
            }
            return@withContext ApplyResult.Queued("player is offline - ${deltas.size} item key(s) queued for delivery on next login")
        }
        val moves = Moves()
        for ((itemKey, delta) in deltas) {
            applyDelta(itemKey, delta, forms[itemKey], moves, player.inventory)
        }
        takeFromCursor(player, moves)
        val at = player.location
        spillInRegion(holder, moves, at.world, at, sink)
        services.differ.rebaseline(holder, player.inventory.toItemTotals().withCursor(player))
        moves.reason?.let(ApplyResult::Failed) ?: ApplyResult.Ok
    }

/** Apply per-player ender inventory. */
internal suspend fun MaterialRestorer.applyToEnderChest(
    holder: HolderId.EnderChest,
    deltas: Map<ItemKey, Long>,
    forms: Map<ItemKey, ByteArray>,
    job: RollbackJobId,
    sink: MutableCollection<Spill>,
): ApplyResult =
    withContext(services.schedulers.entity(holder.uuid)) {
        val player = playerOf(holder.uuid)
        if (player == null) {
            services.atomically {
                services.pendingDeliveries.enqueueAll(holder.uuid, deltas, job, System.currentTimeMillis())
            }
            return@withContext ApplyResult.Queued(
                "player is offline - ${deltas.size} item key(s) queued for delivery to the main inventory on next login",
            )
        }
        val moves = Moves()
        val enderChest = player.enderChest
        for ((itemKey, delta) in deltas) {
            applyDelta(itemKey, delta, forms[itemKey], moves, enderChest)
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
