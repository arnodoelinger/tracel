package com.tracel.model.lot

import com.tracel.model.holder.HolderId
import com.tracel.model.id.Quantity
import com.tracel.model.id.Seq

/**
 * One entry in a holder's FIFO queue for one item key: how much of [lot]
 * currently sits at [holder], and its place in line.
 *
 * Withdrawing always consumes the lowest [fifoSeq] first.
 */
public data class AccountLot(
    public val holder: HolderId,
    public val lot: Lot,
    public val remaining: Quantity,
    public val fifoSeq: Seq,
)
