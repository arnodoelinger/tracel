package com.tracel.engine.rollback.plan.step

import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.item.Quantity
import com.tracel.model.lot.LotId
import com.tracel.model.transaction.TxnId
import java.util.*

/** One action the causal closure found necessary to reclaim traced material. */
public sealed interface RollbackStep {
    /** Take [quantity] of [lotId], currently sitting at [holder]. */
    public data class Take(
        public val lotId: LotId,
        public val quantity: Quantity,
        public val holder: HolderId,
    ) : RollbackStep

    /**
     * Many [Take]s at once: lots that sit together at [holder], untouched since they were traced, each its own root.
     *
     * Same meaning as one [Take] per lot, in [lots] order.
     */
    public class TakeRun(
        public val lots: LongArray,
        public val quantities: LongArray,
        public val holder: HolderId,
    ) : RollbackStep {
        init {
            require(lots.size == quantities.size && lots.isNotEmpty()) { "a run of ${lots.size} lots and ${quantities.size} quantities" }
        }

        public val size: Int get() = lots.size

        /** @return the lot at index [i]. */
        public fun lotAt(i: Int): LotId = LotId(lots[i])

        /** @return the [Take]s in this run, in [lots] order. */
        public fun takes(): List<Take> = List(lots.size) { Take(LotId(lots[it]), Quantity(quantities[it]), holder) }

        override fun equals(other: Any?): Boolean =
            other is TakeRun && holder == other.holder && lots.contentEquals(other.lots) &&
                    quantities.contentEquals(other.quantities)

        override fun hashCode(): Int =
            (holder.hashCode() * 31 + lots.contentHashCode()) * 31 + quantities.contentHashCode()

        override fun toString(): String = "TakeRun(${lots.size} lots at $holder)"
    }

    /** [lotId] was burned; nothing is left to take, so a replacement is minted instead. */
    public data class Mint(
        public val lotId: LotId,
        public val quantity: Quantity,
        public val reason: SinkKind
    ) : RollbackStep

    /** Same as [Mint], but the player who owes it back is offline right now. */
    public data class Debt(
        public val lotId: LotId,
        public val quantity: Quantity,
        public val player: UUID
    ) : RollbackStep

    /**
     * A craft, undone: every living piece of its output is destroyed and every one of its
     * [inputs] — not only the traced one — goes back to [holder], because that is mechanically
     * what reversing a craft means.
     */
    public data class Unmake(
        public val outputs: List<UnmadeOutput>,
        public val inputs: List<LotContribution>,
        public val craftedBy: TxnId,
        public val holder: HolderId,
    ) : RollbackStep
}
