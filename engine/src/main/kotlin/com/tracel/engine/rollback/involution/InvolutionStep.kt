package com.tracel.engine.rollback.involution

import com.tracel.engine.ledger.Product
import com.tracel.model.holder.HolderId
import com.tracel.model.id.LotId
import com.tracel.model.id.Quantity
import com.tracel.model.item.ItemKey

/**
 * Ledger steps that reverse an applied job.
 *
 * Opposite order: give-backs first, re-craft last.
 */
public sealed interface InvolutionStep {
    /** Move delivered stock back to the holder the rollback took it from. */
    public data class Return(
        public val itemKey: ItemKey,
        public val quantity: Quantity,
        public val from: HolderId,
        public val to: HolderId,
        public val lotId: LotId,
    ) : InvolutionStep

    /**
     * Burn the compensation.
     *
     * The original loss stays lost.
     */
    public data class Retract(
        public val itemKey: ItemKey,
        public val quantity: Quantity,
        public val from: HolderId,
        public val originalLot: LotId? = null,
        public val compensationLot: LotId? = null,
    ) : InvolutionStep

    /**
     * Inverse of unmake operation.
     *
     * Consume restored inputs, recreate every output piece.
     */
    public data class Remake(
        public val outputs: List<RemakeOutput>,
        public val product: Product,
        public val inputs: List<RemakeInput>,
    ) : InvolutionStep
}
