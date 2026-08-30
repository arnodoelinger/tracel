package com.tracel.engine.log

import com.tracel.engine.ledger.LotRepository
import com.tracel.engine.rollback.involution.InvolutionStep
import com.tracel.model.flow.FlowLot
import com.tracel.model.id.Seq
import com.tracel.model.id.TxnId
import com.tracel.model.transaction.Transaction

/**
 * The append-only log a [Transaction] belongs to — conceptually the source of truth
 * [LotRepository]'s lot / edge / placement tables are a derived, rebuildable projection of.
 *
 * A transaction is never edited or removed once appended; a correction is a new transaction,
 * the same rule [InvolutionStep] follows for undoing a rollback.
 */
public interface TransactionLog {
    /** Appends a new [transaction] to the log. Throws if a transaction with the same id already exists. */
    public suspend fun append(transaction: Transaction)

    /** Finds a transaction by its [id], or returns `null` if it does not exist. */
    public suspend fun find(id: TxnId): Transaction?

    /** Transactions matching [filter], newest first. */
    public suspend fun query(filter: LookupFilter): List<Transaction>

    /** Which lots the transaction at [seq] moved. */
    public suspend fun lotsAt(seq: Seq): List<FlowLot>

    /** The same for many transactions at once. */
    public suspend fun lotsAtAll(seqs: List<Seq>): Map<Seq, List<FlowLot>>
}
