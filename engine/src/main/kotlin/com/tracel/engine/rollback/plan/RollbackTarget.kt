package com.tracel.engine.rollback.plan

import com.tracel.model.holder.HolderId
import com.tracel.model.lot.LotId

/**
 * Where a rollback puts what it reclaims.
 *
 * [Uniform] is what `/tracel rollback` meant when it took one lot id and a player: everything
 * reclaimed lands in one place, because an admin asked for it by hand.
 *
 * [PerRoot] is what a filter means. "Undo what this player did in the last hour" is not one
 * destination — the gold goes back in the box it came out of, the stone goes back to the
 * coordinate it was mined from — and collapsing that into a single holder would turn a rollback
 * into a very tidy theft.
 */
public sealed interface RollbackTarget {
    /** Everything lands at [holder]. */
    public data class Uniform(public val holder: HolderId) : RollbackTarget

    /** Everything traced from a root lot lands at that root's own holder in [byRoot]. */
    public data class PerRoot(public val byRoot: Map<LotId, HolderId>) : RollbackTarget
}
