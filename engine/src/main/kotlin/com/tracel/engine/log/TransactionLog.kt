package com.tracel.engine.log

import com.tracel.engine.log.lookup.LookupFilter
import com.tracel.model.flow.FlowLot
import com.tracel.model.log.Seq
import com.tracel.model.transaction.Transaction
import com.tracel.model.transaction.TxnId

/** The append-only record of material moving: every [Transaction] the ledger ever committed. */
public interface TransactionLog {
    /** Appends [transaction]. Throws if one with the same id is already there, because the log never overwrites. */
    public suspend fun append(transaction: Transaction)

    /** The transaction with [id], or `null` if there is none. */
    public suspend fun find(id: TxnId): Transaction?

    /** Transactions that [filter] lets through, newest first, after its offset and up to its limit. */
    public suspend fun query(filter: LookupFilter): List<Transaction>

    /** The lots the transaction at [seq] moved, with what each moved of them; empty if there is no such transaction. */
    public suspend fun lotsAt(seq: Seq): List<FlowLot>

    /** [lotsAt] for many transactions at once. A transaction that moved no lots is absent. */
    public suspend fun lotsAtAll(seqs: List<Seq>): Map<Seq, List<FlowLot>> {
        if (seqs.isEmpty()) return emptyMap()
        val out = HashMap<Seq, List<FlowLot>>()
        for (seq in seqs) {
            val lots = lotsAt(seq)
            if (lots.isNotEmpty()) out[seq] = lots
        }
        return if (out.isEmpty()) emptyMap() else out
    }
}
