package com.tracel.engine.log

import com.tracel.engine.ownership.SingleWriterGuard
import com.tracel.model.id.TxnId
import com.tracel.model.transaction.Transaction

/** In-memory [TransactionLog]. */
public class InMemoryTransactionLog : TransactionLog {
    private val writer = SingleWriterGuard()
    private val transactions = mutableMapOf<TxnId, Transaction>()

    override fun append(transaction: Transaction) {
        writer.checkIn()
        check(transaction.id !in transactions) { "transaction ${transaction.id} already appended — the log is append-only" }
        transactions[transaction.id] = transaction
    }

    override fun find(id: TxnId): Transaction? = transactions[id]

    override fun query(filter: LookupFilter): List<Transaction> = transactions.values
        .asSequence()
        .filter { it.matches(filter) }
        .sortedByDescending { it.seq.raw }
        .drop(filter.offset)
        .take(filter.limit)
        .toList()

    private fun Transaction.matches(filter: LookupFilter): Boolean {
        if (filter.since != null && epochMillis < filter.since) return false
        if (filter.until != null && epochMillis > filter.until) return false
        if (filter.causes.isNotEmpty() && cause !in filter.causes) return false

        val touched = flows.flatMap { listOf(it.source, it.destination) } + listOfNotNull(causedBy)
        if (filter.holders.isNotEmpty() && touched.none { it in filter.holders }) return false
        if (filter.excludedHolders.isNotEmpty() && touched.any { it in filter.excludedHolders }) return false
        if (filter.material != null && flows.none { it.itemKey.material == filter.material }) return false

        return true
    }

    public fun all(): Collection<Transaction> = transactions.values
}
