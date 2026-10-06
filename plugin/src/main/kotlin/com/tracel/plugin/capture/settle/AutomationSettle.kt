package com.tracel.plugin.capture.settle

import com.tracel.model.cause.CauseKind
import com.tracel.model.holder.HolderId
import com.tracel.plugin.adapter.item.*
import com.tracel.plugin.capture.MaterialCapture
import com.tracel.plugin.capture.commit.CommitQueue
import com.tracel.plugin.services.TracelServices
import java.util.*
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.inventory.Inventory

/** What hoppers and droppers did, read a tick after the fact and booked together for one region. */
internal class AutomationSettle(
    private val services: TracelServices,
    private val capture: MaterialCapture,
    private val commits: CommitQueue,
) {
    private class SettleBatch(val anchor: Location, val holders: HashMap<HolderId, Inventory>) {
        var closed = false
    }

    private val settleBatches = ThreadLocal.withInitial { ArrayList<SettleBatch>() }

    /**
     * Books what automation really did to [inventories], read a tick later and diffed together.
     *
     * `Paper` fires the move event before it knows whether the item fits: a sorter's filter hopper or a
     * dropper facing a full chest fires it every attempt and moves nothing. Diffing after the fact books
     * only what moved, and a chain that moved in one tick pairs up as moves, not burns and mints.
     */
    fun settleLater(at: Location, cause: CauseKind, inventories: Map<HolderId, Inventory>) {
        val open = settleBatches.get()
        open.removeIf { synchronized(it) { it.closed } }
        for (candidate in open) {
            if (!runCatching { Bukkit.isOwnedByCurrentRegion(candidate.anchor) }.getOrDefault(false)) continue
            synchronized(candidate) {
                if (!candidate.closed) {
                    candidate.holders.putAll(inventories)
                    return
                }
            }
        }
        val batch = SettleBatch(at.clone(), HashMap(inventories))
        open += batch
        val ticket = services.pendingCaptures.owed()
        val scheduled = runCatching {
            Bukkit.getRegionScheduler().runDelayed(services.plugin, at, {
                try {
                    val holders = synchronized(batch) {
                        batch.closed = true
                        HashMap(batch.holders)
                    }
                    settle(holders, cause)
                } finally {
                    services.pendingCaptures.done(ticket)
                }
            }, 1L)
        }.isSuccess
        if (!scheduled) {
            open.remove(batch)
            services.pendingCaptures.done(ticket)
        }
    }

    private fun settle(batch: Map<HolderId, Inventory>, cause: CauseKind) {
        val totals = batch.mapValues { (_, inventory) -> inventory.toItemTotals() }
        val epochMillis = System.currentTimeMillis()
        if (totals.keys.all { services.differ.seeded(it) }) {
            val deltas = totals.entries.flatMap { (holder, now) -> services.differ.diffIfSeeded(holder, now).orEmpty() }
            capture.many(cause, null, deltas, epochMillis)
            return
        }
        commits.committing("$cause settle of ${totals.size} holders") {
            val deltas = totals.entries.flatMap { (holder, now) -> services.differ.diff(holder, now) }
            if (deltas.isNotEmpty()) services.capture.record(deltas, epochMillis, cause, null)
        }
    }
}
