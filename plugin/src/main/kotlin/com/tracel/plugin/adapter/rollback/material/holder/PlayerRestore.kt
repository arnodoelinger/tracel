package com.tracel.plugin.adapter.rollback.material.holder

import com.tracel.annotations.Unstable
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.model.rollback.RollbackJobId
import com.tracel.plugin.adapter.item.heldTotals
import com.tracel.plugin.adapter.rollback.material.deliverPending
import com.tracel.plugin.adapter.rollback.material.item.Moves
import com.tracel.plugin.adapter.rollback.material.item.PendingWorn
import com.tracel.plugin.adapter.rollback.material.item.WornStacks
import com.tracel.plugin.adapter.rollback.material.item.applyDelta
import com.tracel.plugin.adapter.rollback.material.spill.spillInRegion
import com.tracel.plugin.adapter.world.playerOf
import com.tracel.plugin.rollback.material.ApplyResult
import com.tracel.plugin.rollback.material.MaterialRestorer
import com.tracel.plugin.rollback.material.spill.Spill
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bukkit.Bukkit
import java.util.*

@Unstable
internal const val STORAGE_SLOTS = 36

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
            if (worn != null) {
                for ((itemKey, delta) in deltas) {
                    if (delta <= 0L || !WornStacks.wears(itemKey)) continue
                    var left = delta
                    while (left > 0L) {
                        val real = worn.next(itemKey) ?: break
                        PendingWorn.keep(holder.uuid, itemKey, real)
                        left -= real.amount
                    }
                }
            }
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
        takeFromMenu(player, moves)
        wearGiven(player, deltas)
        val at = player.location
        spillInRegion(holder, moves, at.world, at, sink)
        services.differ.rebaseline(holder, player.heldTotals())
        moves.failure() ?: ApplyResult.Ok
    }

internal fun MaterialRestorer.deliverIfBack(uuid: UUID) {
    val player = Bukkit.getPlayer(uuid) ?: return
    if (!player.isOnline || player.isDead) return
    services.scope.launch { deliverPending(player) }
}
