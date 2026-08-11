package com.tracel.engine.log

import com.tracel.engine.concurrency.SingleWriterGuard
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
}
