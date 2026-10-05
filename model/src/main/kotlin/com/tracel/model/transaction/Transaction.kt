package com.tracel.model.transaction

import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowLot
import com.tracel.model.holder.HolderId
import com.tracel.model.id.Seq
import com.tracel.model.id.TxnId
import com.tracel.model.world.BlockPos

/**
 * One entry in the append-only log: a set of [flows] that happened together,
 * for one [cause]. Once written, a transaction is never edited — a rollback is
 * a new transaction that compensates for an old one, never a change to it.
 */
public data class Transaction(
    public val id: TxnId,
    public val seq: Seq,
    public val epochMillis: Long,
    public val cause: CauseKind,
    public val causedBy: HolderId?,
    public val flows: List<Flow>,
    public val lots: List<FlowLot> = emptyList(),
    public val at: BlockPos? = null,
)
