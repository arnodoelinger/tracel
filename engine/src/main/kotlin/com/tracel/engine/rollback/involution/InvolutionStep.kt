package com.tracel.engine.rollback.involution

import com.tracel.engine.ledger.Product
import com.tracel.engine.rollback.plan.RollbackStep
import com.tracel.model.holder.HolderId
import com.tracel.model.id.LotId
import com.tracel.model.id.Quantity
import com.tracel.model.item.ItemKey

/**
 * One action needed to undo a rollback.
 *
 * Applying a rollback and then its involution restores the original state.
 */
public sealed interface InvolutionStep {
    /** Undoes a [RollbackStep.Take]. */
    public data class Return(
        public val itemKey: ItemKey,
        public val quantity: Quantity,
        public val from: HolderId,
        public val to: HolderId,
        public val lotId: LotId,
    ) : InvolutionStep

    /**
     * Removes compensation created by [RollbackStep.Mint] or [RollbackStep.Debt].
     *
     * The original loss is not restored.
     */
    public data class Retract(
        public val itemKey: ItemKey,
        public val quantity: Quantity,
        public val from: HolderId,
        public val originalLot: LotId? = null,
        public val compensationLot: LotId? = null,
    ) : InvolutionStep

    /** Recreates an output from the inputs consumed by [RollbackStep.Unmake]. */
    public data class Remake(
        public val outputs: List<RemakeOutput>,
        public val product: Product,
        public val inputs: List<RemakeInput>,
    ) : InvolutionStep
}

/** A piece of a crafted output returned to its original holder. */
public data class RemakeOutput(
    public val lotId: LotId,
    public val quantity: Quantity,
    public val holder: HolderId,
)

/** An input lot restored by re-crafting an output. */
public data class RemakeInput(
    public val lotId: LotId,
    public val itemKey: ItemKey,
    public val quantity: Quantity,
)
