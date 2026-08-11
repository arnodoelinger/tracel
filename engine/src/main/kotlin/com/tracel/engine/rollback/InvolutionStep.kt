package com.tracel.engine.rollback

import com.tracel.engine.ledger.Ingredient
import com.tracel.engine.ledger.Product
import com.tracel.model.holder.HolderId
import com.tracel.model.id.Quantity
import com.tracel.model.item.ItemKey

/**
 * One action needed to reverse an already-applied [RollbackStep] — "involution" as in the
 * mathematical sense, an operation that is its own inverse applied twice: rollback undoes a
 * bad transaction, and this undoes a rollback the same way.
 *
 * Deliberately expressed in item keys and quantities, never specific lot ids: by the time
 * anyone undoes a job, ordinary gameplay may already have moved, split, or spent whatever it
 * recovered. [InvolutionExecutor] goes through the same FIFO ledger operations everything
 * else does, and fails the same honest way [com.tracel.engine.ledger.LotLedger.withdraw] does
 * if the material genuinely is not there anymore.
 */
public sealed interface InvolutionStep {
    /** Moves [quantity] of [itemKey] from [from] back to [to] — undoes a [RollbackStep.Take]. */
    public data class Return(
        public val itemKey: ItemKey,
        public val quantity: Quantity,
        public val from: HolderId,
        public val to: HolderId,
    ) : InvolutionStep

    /**
     * Burns [quantity] of [itemKey] at [from] — undoes a [RollbackStep.Mint] or
     * [RollbackStep.Debt]. Only the compensation is reversed; whatever was actually lost
     * (burned in lava, for instance) stays lost, which is exactly the point.
     */
    public data class Retract(
        public val itemKey: ItemKey,
        public val quantity: Quantity,
        public val from: HolderId,
    ) : InvolutionStep

    /** Re-crafts [ingredients] into [product] — undoes a [RollbackStep.Unmake]. */
    public data class Remake(
        public val ingredients: List<Ingredient>,
        public val product: Product,
    ) : InvolutionStep
}
