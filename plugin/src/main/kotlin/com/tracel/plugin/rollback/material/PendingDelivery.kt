package com.tracel.plugin.rollback.material

import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.plugin.adapter.item.toItemTotals
import com.tracel.plugin.adapter.item.withCursor
import com.tracel.plugin.rollback.material.item.Moves
import com.tracel.plugin.rollback.material.item.applyDelta
import com.tracel.plugin.rollback.material.item.formsFor
import com.tracel.plugin.rollback.material.spill.Spill
import com.tracel.plugin.rollback.material.spill.recordSpills
import com.tracel.plugin.rollback.material.spill.spillInRegion
import com.tracel.plugin.rollback.result.report.RestorationReport
import com.tracel.storage.ports.ledger.PendingDelivery
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.logging.Level
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.bukkit.entity.Player

/**
 * Hands a player whatever was queued for them while they were offline.
 *
 * Claims the queue before touching the inventory, so a failed write can requeue instead of losing the claim.
 */
suspend fun MaterialRestorer.deliverPending(player: Player): RestorationReport {
    val claimed = services.atomically { services.pendingDeliveries.claimFor(player.uniqueId) }
    if (claimed.isEmpty()) return RestorationReport(emptyMap())

    val forms = formsFor(mapOf(HolderId.Player(player.uniqueId) to claimed.associate { it.itemKey to it.delta }))

    val holder = HolderId.Player(player.uniqueId)
    val sink = ConcurrentLinkedQueue<Spill>()
    val report = try {
        withContext(services.schedulers.entity(player.uniqueId)) {
            // Already claimed
            if (!player.isOnline) {
                requeue(player.uniqueId, claimed)
                return@withContext RestorationReport(emptyMap())
            }
            val moves = Moves()
            for ((_, itemKey, delta) in claimed) {
                applyDelta(itemKey, delta, forms[itemKey], moves, player.inventory)
            }
            // Full inventory on login is ordinary: spill it all
            val at = player.location
            spillInRegion(holder, moves, at.world, at, sink)
            services.differ.rebaseline(holder, player.inventory.toItemTotals().withCursor(player))

            val reason = moves.reason
            if (reason == null) {
                RestorationReport(emptyMap(), emptyMap(), sink.size)
            } else {
                logger.log(Level.WARNING, "delivering queued material to $holder was incomplete: $reason")
                RestorationReport(mapOf(holder to reason), emptyMap(), sink.size)
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        // Claim committed; failure without requeue is a delivery nobody is owed
        requeue(player.uniqueId, claimed)
        logger.log(Level.WARNING, "queued material for $holder could not be delivered and was re-queued", failure)
        RestorationReport(mapOf(holder to (failure.message ?: failure::class.java.simpleName)))
    }
    recordSpills(sink)
    return report
}

/** Puts a claim back on the queue, keeping each entry under the job that owed it. */
internal suspend fun MaterialRestorer.requeue(player: UUID, claimed: List<PendingDelivery>) {
    val now = System.currentTimeMillis()
    runCatching {
        services.atomically {
            for ((job, entries) in claimed.groupBy { it.job }) {
                val deltas = LinkedHashMap<ItemKey, Long>()
                for ((_, itemKey, delta) in entries) deltas.merge(itemKey, delta, Long::plus)
                services.pendingDeliveries.enqueueAll(player, deltas, job, now)
            }
        }
    }.onFailure {
        logger.log(Level.SEVERE, "queued material for $player was claimed and could not be re-queued", it)
    }
}
