package com.tracel.model.lot

import com.tracel.model.holder.HolderId
import com.tracel.model.id.LotId
import com.tracel.model.id.Quantity
import com.tracel.model.id.RollbackJobId
import com.tracel.model.id.TxnId

/**
 * How a lot's life continued after it was created — the edges that turn
 * individual lots into the provenance graph.
 */
public sealed interface LotEdge {
    public val child: LotId
    public val parent: LotId
    public val quantity: Quantity

    /** [parent] was partly withdrawn: it retires, replaced by [child] (the taken share) and a sibling (the rest). */
    public data class Split(
        override val child: LotId,
        override val parent: LotId,
        override val quantity: Quantity,
    ) : LotEdge

    /**
     * [parent] was consumed as a crafting ingredient; [child] is the crafted
     * output. [producedAt] is where the output landed at the moment of
     * crafting — fixed here rather than looked up later, because by the time
     * a rollback needs it the output may have moved, split, or been consumed
     * by a second craft, none of which should change where undoing *this*
     * craft puts its ingredients back.
     */
    public data class Transform(
        override val child: LotId,
        override val parent: LotId,
        override val quantity: Quantity,
        public val craftedBy: TxnId,
        public val producedAt: HolderId,
    ) : LotEdge

    /** [child] is a rollback mint standing in for [parent], which could not be physically recovered. */
    public data class Compensate(
        override val child: LotId,
        override val parent: LotId,
        override val quantity: Quantity,
        public val rollbackJob: RollbackJobId,
    ) : LotEdge
}
