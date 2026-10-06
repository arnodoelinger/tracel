package com.tracel.engine.store

import com.tracel.model.log.Seq
import com.tracel.model.rollback.RollbackJobId
import com.tracel.model.transaction.TxnId

/** Durable ID allocation: no number is ever handed out twice, across restarts too. */
public interface Counters {
    /** How many sequence numbers were handed out since start: one per record written to a log. */
    public val seqIssued: Long

    /** @return a new transaction ID. */
    public suspend fun nextTxnId(): TxnId

    /** @return a new sequence number. */
    public suspend fun nextSeq(): Seq

    /** @return the first of [count] consecutive new sequence numbers. */
    public suspend fun nextSeqRange(count: Int): Seq

    /** @return a new rollback job ID. */
    public suspend fun nextRollbackJobId(): RollbackJobId

    /** @return the transaction ID the next [nextTxnId] would hand out, without taking it. */
    public suspend fun peekTxnId(): Long
}
