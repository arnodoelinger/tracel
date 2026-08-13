package com.tracel.storage.pending

import com.tracel.engine.ownership.SingleWriterGuard
import com.tracel.model.id.RollbackJobId
import com.tracel.model.item.ItemKey
import com.tracel.storage.intern.Interning
import com.tracel.storage.schema.PendingDeliveriesTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID

/**
 * One unit of material [PhysicalRestorer][com.tracel.plugin.rollback.PhysicalRestorer] owes (or owes back from) a player
 * who was offline at the time.
 */
data class PendingDelivery(val id: Long, val itemKey: ItemKey, val delta: Long, val job: RollbackJobId)

/** `SQLite`-backed queue of [PendingDelivery]s. */
class SqlitePendingDeliveryRepository(private val db: Database) {
    private val writer = SingleWriterGuard()

    /**
     * Queues every entry in [deltas] for [player] as one durable batch — a single transaction.
     * A crash partway through a multi-item-key restore must not leave some of it queued and
     * the rest silently lost forever.
     */
    fun enqueueAll(player: UUID, deltas: Map<ItemKey, Long>, job: RollbackJobId, nowMillis: Long) {
        writer.checkIn()
        transaction(db) {
            for ((itemKey, delta) in deltas) {
                PendingDeliveriesTable.insert {
                    it[playerUuid] = player.toString()
                    it[itemKeyId] = Interning.internItemKey(itemKey)
                    it[PendingDeliveriesTable.delta] = delta
                    it[jobId] = job.raw
                    it[createdMillis] = nowMillis
                }
            }
        }
    }

    /** Atomically reads and deletes every entry owed to [player], in one transaction. */
    fun claimFor(player: UUID): List<PendingDelivery> {
        writer.checkIn()
        return transaction(db) {
            val rows = PendingDeliveriesTable.selectAll()
                .where { PendingDeliveriesTable.playerUuid eq player.toString() }
                .map {
                    PendingDelivery(
                        it[PendingDeliveriesTable.id],
                        Interning.resolveItemKey(it[PendingDeliveriesTable.itemKeyId]),
                        it[PendingDeliveriesTable.delta],
                        RollbackJobId(it[PendingDeliveriesTable.jobId]),
                    )
                }
            if (rows.isNotEmpty()) {
                PendingDeliveriesTable.deleteWhere { PendingDeliveriesTable.playerUuid eq player.toString() }
            }
            rows
        }
    }
}
