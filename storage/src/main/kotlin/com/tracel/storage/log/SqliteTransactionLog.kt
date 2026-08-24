package com.tracel.storage.log

import com.tracel.annotations.CauseKind
import com.tracel.engine.log.LookupFilter
import com.tracel.engine.log.TransactionLog
import com.tracel.engine.ownership.SingleWriterGuard
import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowKind
import com.tracel.model.id.Quantity
import com.tracel.model.id.Seq
import com.tracel.model.id.TxnId
import com.tracel.model.transaction.Transaction
import com.tracel.storage.intern.Interning
import com.tracel.storage.schema.FlowsTable
import com.tracel.storage.schema.ItemKeysTable
import com.tracel.storage.schema.TransactionsTable
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
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

    override fun query(filter: LookupFilter): List<Transaction> = sqlTransaction(db) {
        val holderIds = filter.holders.mapNotNull(Interning::findHolderId)
        if (filter.holders.isNotEmpty() && holderIds.isEmpty()) return@sqlTransaction emptyList()
        val excludedIds = filter.excludedHolders.mapNotNull(Interning::findHolderId)

        val materialItemKeyIds = filter.material?.let { material ->
            ItemKeysTable.select(ItemKeysTable.id).where { ItemKeysTable.material eq material }.map { it[ItemKeysTable.id] }
        }
        if (materialItemKeyIds != null && materialItemKeyIds.isEmpty()) return@sqlTransaction emptyList()

        val holderMatchedTxnIds = holderIds.takeIf { it.isNotEmpty() }?.let { touchingTxnIds(it) }
        val materialMatchedTxnIds = materialItemKeyIds?.let { ids ->
            FlowsTable.select(FlowsTable.txnId).where { FlowsTable.itemKeyId inList ids }.map { it[FlowsTable.txnId] }.toSet()
        }
        val excludedTxnIds = excludedIds.takeIf { it.isNotEmpty() }?.let { touchingTxnIds(it) } ?: emptySet()

        var candidateIds: Set<Long>? = holderMatchedTxnIds
        candidateIds = materialMatchedTxnIds?.let { candidateIds?.intersect(it) ?: it } ?: candidateIds
        if (candidateIds != null && candidateIds.isEmpty()) return@sqlTransaction emptyList()

        val conditions = mutableListOf<Op<Boolean>>()
        candidateIds?.let { conditions += TransactionsTable.id inList it }
        filter.since?.let { conditions += TransactionsTable.epochMillis greaterEq it }
        filter.until?.let { conditions += TransactionsTable.epochMillis lessEq it }
        if (filter.causes.isNotEmpty()) conditions += TransactionsTable.cause inList filter.causes.map { it.name }
        if (excludedTxnIds.isNotEmpty()) conditions += TransactionsTable.id notInList excludedTxnIds

        var rowsQuery = TransactionsTable.selectAll()
        if (conditions.isNotEmpty()) rowsQuery = rowsQuery.where { conditions.reduce { a, b -> a and b } }
        val rows = rowsQuery.orderBy(TransactionsTable.seq, SortOrder.DESC)
            .limit(filter.limit)
            .offset(filter.offset.toLong())
            .toList()

        val txnIds = rows.map { it[TransactionsTable.id] }
        val flowsByTxn = if (txnIds.isEmpty()) {
            emptyMap()
        } else {
            FlowsTable.selectAll().where { FlowsTable.txnId inList txnIds }
                .orderBy(FlowsTable.idx)
                .groupBy({ it[FlowsTable.txnId] }, { it.toFlow() })
        }

        rows.map { it.toTransaction(flowsByTxn[it[TransactionsTable.id]].orEmpty()) }
    }

    private fun touchingTxnIds(holderIds: List<Long>): Set<Long> {
        val fromFlows = FlowsTable.select(FlowsTable.txnId)
            .where { (FlowsTable.sourceHolderId inList holderIds) or (FlowsTable.destinationHolderId inList holderIds) }
            .map { it[FlowsTable.txnId] }
        val fromCause = TransactionsTable.select(TransactionsTable.id)
            .where { TransactionsTable.causedByHolderId inList holderIds }
            .map { it[TransactionsTable.id] }
        return (fromFlows + fromCause).toSet()
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
