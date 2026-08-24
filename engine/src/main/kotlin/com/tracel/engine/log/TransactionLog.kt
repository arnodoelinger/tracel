package com.tracel.engine.log

import com.tracel.model.id.TxnId
import com.tracel.model.transaction.Transaction

/**
 * The append-only log a [Transaction] belongs to — conceptually the source of truth
 * [com.tracel.engine.ledger.LotRepository]'s lot / edge / placement tables are a derived,
 * rebuildable projection of.
 *
 * A transaction is never edited or removed once appended; a correction is a new transaction,
 * the same rule [com.tracel.engine.rollback.involution.InvolutionStep] follows for undoing a rollback.
 */
public interface TransactionLog {
    /** Appends a new [transaction] to the log. Throws if a transaction with the same id already exists. */
    public fun append(transaction: Transaction)

    /** Finds a transaction by its [id], or returns `null` if it does not exist. */
    public fun find(id: TxnId): Transaction?

    /** Transactions matching [filter], newest first. */
    public fun query(filter: LookupFilter): List<Transaction>
}
