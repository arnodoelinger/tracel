package com.tracel.storage.log

import com.tracel.annotations.CauseKind
import com.tracel.engine.concurrency.SingleWriterGuard
import com.tracel.engine.log.TransactionLog
import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowKind
import com.tracel.model.id.Quantity
import com.tracel.model.id.Seq
import com.tracel.model.id.TxnId
import com.tracel.model.transaction.Transaction
import com.tracel.storage.intern.Interning
import com.tracel.storage.schema.FlowsTable
import com.tracel.storage.schema.TransactionsTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction as sqlTransaction

/**
 * `SQLite`-backed [TransactionLog]. Unlike [com.tracel.storage.ledger.SqliteLotRepository], a
 * transaction's flows are all written in one call — a transaction's flows are never partially
 * meaningful, so there is no crash-safety reason to split them across separate write calls the
 * way a rollback job's steps are.
 */
class SqliteTransactionLog(private val db: Database) : TransactionLog {
    private val writer = SingleWriterGuard()

    override fun append(transaction: Transaction) {
        writer.checkIn()
        sqlTransaction(db) {
            check(
                TransactionsTable.selectAll().where { TransactionsTable.id eq transaction.id.raw }.none()
            ) { "transaction ${transaction.id} already appended — the log is append-only" }

            TransactionsTable.insert {
                it[id] = transaction.id.raw
                it[seq] = transaction.seq.raw
                it[epochMillis] = transaction.epochMillis
                it[cause] = transaction.cause.name
                it[causedByHolderId] = transaction.causedBy?.let(Interning::internHolder)
            }

            transaction.flows.forEachIndexed { index, flow ->
                FlowsTable.insert {
                    it[txnId] = transaction.id.raw
                    it[idx] = index
                    it[itemKeyId] = Interning.internItemKey(flow.itemKey)
                    it[quantity] = flow.quantity.raw
                    it[sourceHolderId] = Interning.internHolder(flow.source)
                    it[destinationHolderId] = Interning.internHolder(flow.destination)
                    it[kind] = flow.kind.name
                }
            }
        }
    }

    override fun find(id: TxnId): Transaction? = sqlTransaction(db) {
        val row = TransactionsTable.selectAll().where { TransactionsTable.id eq id.raw }.singleOrNull() ?: return@sqlTransaction null
        val flows = FlowsTable.selectAll().where { FlowsTable.txnId eq id.raw }
            .orderBy(FlowsTable.idx)
            .map { it.toFlow() }
        row.toTransaction(flows)
    }

    private fun ResultRow.toTransaction(flows: List<Flow>): Transaction = Transaction(
        TxnId(this[TransactionsTable.id]),
        Seq(this[TransactionsTable.seq]),
        this[TransactionsTable.epochMillis],
        CauseKind.valueOf(this[TransactionsTable.cause]),
        this[TransactionsTable.causedByHolderId]?.let(Interning::resolveHolder),
        flows,
    )

    private fun ResultRow.toFlow(): Flow = Flow(
        Interning.resolveItemKey(this[FlowsTable.itemKeyId]),
        Quantity(this[FlowsTable.quantity]),
        Interning.resolveHolder(this[FlowsTable.sourceHolderId]),
        Interning.resolveHolder(this[FlowsTable.destinationHolderId]),
        FlowKind.valueOf(this[FlowsTable.kind]),
    )
}
