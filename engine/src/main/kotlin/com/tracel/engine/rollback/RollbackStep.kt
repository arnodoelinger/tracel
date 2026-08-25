package com.tracel.engine.rollback

import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.id.LotId
import com.tracel.model.id.Quantity
import com.tracel.model.id.TxnId
import java.util.UUID

/** One action the causal closure found necessary to reclaim traced material. */
public sealed interface RollbackStep {
    /** Take [quantity] of [lotId], currently sitting at [holder]. */
    public data class Take(public val lotId: LotId, public val quantity: Quantity, public val holder: HolderId) : RollbackStep

    /** [lotId] was burned; nothing is left to take, so a replacement is minted instead. */
    public data class Mint(public val lotId: LotId, public val quantity: Quantity, public val reason: SinkKind) : RollbackStep

    /** Same as [Mint], but the player who owes it back is offline right now. */
    public data class Debt(public val lotId: LotId, public val quantity: Quantity, public val player: UUID) : RollbackStep

    /**
     * [outputLot] was crafted from several ingredients. Undoing it destroys
     * the output and restores every one of its [inputs] — not only the
     * traced one — back to [holder], because that is mechanically what
     * reversing the craft means.
     */
    public data class Unmake(
        public val outputLot: LotId,
        public val inputs: List<LotContribution>,
        public val craftedBy: TxnId,
        public val holder: HolderId,
    ) : RollbackStep
}

/** How much of one lot went into a craft. */
public data class LotContribution(public val lotId: LotId, public val quantity: Quantity)
