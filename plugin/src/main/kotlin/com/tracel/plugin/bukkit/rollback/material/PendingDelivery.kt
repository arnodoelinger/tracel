package com.tracel.plugin.bukkit.rollback.material

import com.tracel.engine.ledger.PendingDelivery
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.plugin.bukkit.item.Experience
import com.tracel.plugin.bukkit.item.heldTotals
import com.tracel.plugin.bukkit.item.toItemTotals
import com.tracel.plugin.bukkit.rollback.material.holder.shiftExperience
import com.tracel.plugin.bukkit.rollback.material.holder.takeFromCursor
import com.tracel.plugin.bukkit.rollback.material.holder.takeFromGrid
import com.tracel.plugin.bukkit.rollback.material.holder.takeFromMenu
import com.tracel.plugin.bukkit.rollback.material.item.*
import com.tracel.plugin.bukkit.rollback.material.spill.recordSpills
import com.tracel.plugin.bukkit.rollback.material.spill.spillInRegion
import com.tracel.plugin.rollback.material.MaterialRestorer
import com.tracel.plugin.rollback.material.rewearPlan
import com.tracel.plugin.rollback.material.spill.Spill
import com.tracel.plugin.rollback.result.report.RestorationReport
import com.tracel.plugin.util.runtime.messageOrName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.logging.Level

/**
 * Hands a player whatever was queued for them while they were offline.
 *
 * Claims the queue before touching the inventory, so a failed write can requeue instead of losing the claim.
 */
suspend fun MaterialRestorer.deliverPending(player: Player): RestorationReport {
    val (claimed, wear) = services.atomically {
        services.storage.pendingDeliveries.claimFor(player.uniqueId) to services.storage.pendingWear.claimFor(player.uniqueId)
    }
    if (claimed.isEmpty()) return RestorationReport(emptyMap())

    val forms = formsFor(mapOf(HolderId.Player(player.uniqueId) to claimed.associate { it.itemKey to it.delta }))

    val holder = HolderId.Player(player.uniqueId)
    val sink = ConcurrentLinkedQueue<Spill>()
    val report = try {
        withContext(services.schedulers.entity(player.uniqueId)) {
            // Already claimed
            if (!player.isOnline) {
                requeue(player.uniqueId, claimed, wear)
                return@withContext RestorationReport(emptyMap())
            }
            val moves = Moves()
            val enderMoves = Moves()
            val worn = wornStacks(player, wear)
            for ((_, itemKey, delta, _, stash) in claimed.sortedBy { it.delta > 0L }) {
                if (Experience.isExperience(itemKey)) {
                    player.shiftExperience(delta, moves)
                } else if (stash) {
                    applyDelta(itemKey, delta, forms[itemKey], enderMoves, player.enderChest, worn = worn)
                } else {
                    applyDelta(itemKey, delta, forms[itemKey], moves, player.inventory, worn = worn)
                }
            }
            // Full inventory on login is ordinary: spill it all
            val at = player.location
            takeFromCursor(player, moves)
            takeFromGrid(player, moves)
            takeFromMenu(player, moves)
            spillInRegion(holder, moves, at.world, at, sink)
            services.capturing.differ.rebaseline(holder, player.heldTotals())
            if (claimed.any { it.stash }) {
                val ender = HolderId.PlayerStash(player.uniqueId)
                spillInRegion(ender, enderMoves, at.world, at, sink)
                services.capturing.differ.rebaseline(ender, player.enderChest.toItemTotals())
            }

            val reason = listOfNotNull(moves.reason, enderMoves.reason).joinToString("; ").ifEmpty { null }
            if (reason == null) {
                RestorationReport(emptyMap(), emptyMap(), sink.size)
            } else {
                logger.log(Level.WARNING, "Delivering queued material to $holder was incomplete ($reason).")
                RestorationReport(mapOf(holder to reason), emptyMap(), sink.size)
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        // Claim committed; failure without requeue is a delivery nobody is owed
        requeue(player.uniqueId, claimed, wear)
        logger.log(Level.WARNING, "Queued material for $holder could not be delivered (${failure.messageOrName()}). It was queued again.", failure)
        RestorationReport(mapOf(holder to (failure.messageOrName())))
    }
    recordSpills(sink)
    rewearDelivered(player, claimed)
    return report
}

private suspend fun MaterialRestorer.rewearDelivered(player: Player, claimed: List<PendingDelivery>) {
    if (claimed.none { it.delta > 0L && WornStacks.wears(it.itemKey) }) return
    val mine = setOf(HolderId.Player(player.uniqueId), HolderId.PlayerStash(player.uniqueId))
    for (job in claimed.mapTo(LinkedHashSet()) { it.job }) {
        val record = runCatching { services.atomically { services.storage.jobs.find(job) } }.getOrNull() ?: continue
        val asOf = record.targetTimeMillis ?: continue
        runCatching { rewearPlan(record.plan, record.target, job, asOf, only = mine) }
            .onFailure {
                logger.log(
                    Level.FINE,
                    "Tools delivered to ${player.name} kept their damage as it was (${it.messageOrName()}).",
                    it
                )
            }
    }
}

/** Puts a claim back on the queue, keeping each entry under the job that owed it and the inventory it was owed to. */
internal suspend fun MaterialRestorer.requeue(
    player: UUID,
    claimed: List<PendingDelivery>,
    wear: Map<ItemKey, List<ByteArray>> = emptyMap(),
) {
    val now = System.currentTimeMillis()
    runCatching {
        services.atomically {
            services.storage.pendingWear.keepAll(player, wear)
            for ((owed, entries) in claimed.groupBy { it.job to it.stash }) {
                val deltas = LinkedHashMap<ItemKey, Long>()
                for ((_, itemKey, delta) in entries) deltas.merge(itemKey, delta, Long::plus)
                services.storage.pendingDeliveries.enqueueAll(player, deltas, owed.first, now, owed.second)
            }
        }
    }.onFailure {
        logger.log(Level.SEVERE, "Queued material for $player was claimed, but could not be queued again (${it.messageOrName()}).", it)
    }
}

private fun MaterialRestorer.wornStacks(player: Player, wear: Map<ItemKey, List<ByteArray>>): WornStacks? {
    if (wear.isEmpty()) return null
    val worn = WornStacks()
    for ((itemKey, kept) in wear) {
        for (bytes in kept) {
            try {
                worn.took(itemKey, ItemStack.deserializeBytes(bytes))
            } catch (failure: Exception) {
                logger.log(Level.WARNING, "A worn ${itemKey.material} kept for ${player.name} could not be read back (${failure.messageOrName()}). It comes back new.", failure)
            }
        }
    }
    return worn
}
