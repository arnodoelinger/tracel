package com.tracel.plugin.adapter.rollback.material.holder

import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.model.rollback.RollbackJobId
import com.tracel.plugin.adapter.item.toItemTotals
import com.tracel.plugin.adapter.rollback.material.item.Moves
import com.tracel.plugin.adapter.rollback.material.item.WornStacks
import com.tracel.plugin.adapter.rollback.material.item.applyDelta
import com.tracel.plugin.adapter.rollback.material.spill.spillInRegion
import com.tracel.plugin.adapter.world.playerOf
import com.tracel.plugin.rollback.material.ApplyResult
import com.tracel.plugin.rollback.material.MaterialRestorer
import com.tracel.plugin.rollback.material.spill.Spill
import kotlinx.coroutines.withContext

/** Apply per-player ender inventory. */
internal suspend fun MaterialRestorer.applyToEnderChest(
    holder: HolderId.PlayerStash,
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
                services.pendingDeliveries.enqueueAll(
                    holder.uuid,
                    deltas,
                    job,
                    System.currentTimeMillis(),
                    stash = true
                )
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
        moves.failure() ?: ApplyResult.Ok
    }
