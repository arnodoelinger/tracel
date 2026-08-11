package com.tracel.engine.log

import com.tracel.model.id.TxnId
import com.tracel.model.transaction.Transaction

/**
 * The append-only log a [Transaction] belongs to — conceptually the source of truth
 * [com.tracel.engine.ledger.LotRepository]'s lot / edge / placement tables are a derived,
 * rebuildable projection of. A transaction is never edited or removed once appended;
 * a correction is a new transaction, the same rule [com.tracel.engine.rollback.InvolutionStep]
 * follows for undoing a rollback.
 */
public interface TransactionLog {
    public fun append(transaction: Transaction)
    public fun find(id: TxnId): Transaction?
}
