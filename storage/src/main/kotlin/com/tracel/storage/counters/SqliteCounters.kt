package com.tracel.storage.counters

import com.tracel.engine.ownership.SingleWriterGuard
import com.tracel.model.id.Seq
import com.tracel.model.id.TxnId
import com.tracel.storage.schema.CountersTable
import com.tracel.storage.schema.LotEdgesTable
import com.tracel.storage.schema.LotsTable
import com.tracel.storage.schema.TransactionsTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Durable [TxnId] / [Seq] allocation. A plugin restart must never hand out an id a previous
 * session already used — an in-memory counter that always starts at 1 does exactly that,
 * colliding with whatever [com.tracel.storage.schema.TransactionsTable] rows already exist,
 * and [com.tracel.engine.log.TransactionLog.append]'s duplicate-id check throws for every
 * capture attempt until the counter catches back up on its own.
 *
 * The first call for a counter that has never been persisted bootstraps it from the highest id
 * already in use anywhere that id could have ended up — not just 1 — so this is safe to add to
 * a database that already has real data in it.
 */
class SqliteCounters(private val db: Database) {
    private val writer = SingleWriterGuard()

    /** Allocates a new [TxnId] that has never been used before. */
    fun nextTxnId(): TxnId = TxnId(next("txn") { bootstrapTxn() })

    /** Allocates a new [Seq] that has never been used before. */
    fun nextSeq(): Seq = Seq(next("seq") { bootstrapSeq() })

    /** Allocates a new id for a counter with the given [name] that has never been used before. */
    private fun next(name: String, bootstrap: () -> Long): Long {
        writer.checkIn()
        return transaction(db) {
            val existing = CountersTable.selectAll().where { CountersTable.name eq name }.singleOrNull()
            val value = existing?.get(CountersTable.nextValue) ?: bootstrap()
            if (existing == null) {
                CountersTable.insert {
                    it[CountersTable.name] = name
                    it[nextValue] = value + 1
                }
            } else {
                CountersTable.update({ CountersTable.name eq name }) { it[nextValue] = value + 1 }
            }
            value
        }
    }

    /** Bootstraps the next [TxnId] from the highest id already in use anywhere that id could have ended up. */
    private fun bootstrapTxn(): Long {
        val maxTxn = TransactionsTable.selectAll().mapNotNull { it[TransactionsTable.id] }.maxOrNull() ?: 0L
        val maxCreatedBy = LotsTable.selectAll().mapNotNull { it[LotsTable.createdBy] }.maxOrNull() ?: 0L
        val maxCraftedBy = LotEdgesTable.selectAll().mapNotNull { it[LotEdgesTable.craftedBy] }.maxOrNull() ?: 0L
        return maxOf(maxTxn, maxCreatedBy, maxCraftedBy) + 1
    }

    /** Bootstraps the next [Seq] from the highest id already in use anywhere that id could have ended up. */
    private fun bootstrapSeq(): Long =
        (TransactionsTable.selectAll().mapNotNull { it[TransactionsTable.seq] }.maxOrNull() ?: 0L) + 1
}
