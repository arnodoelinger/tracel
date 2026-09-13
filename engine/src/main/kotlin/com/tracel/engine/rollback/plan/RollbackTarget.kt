package com.tracel.engine.rollback.plan

import com.tracel.model.holder.HolderId
import com.tracel.model.id.LotId

/**
 * Where a rollback puts what it reclaims.
 *
 * [Uniform] is what `/tracel rollback` meant when it took one lot id and a player: everything
 * reclaimed lands in one place, because an admin asked for it by hand.
 *
 * [PerRoot] is what a filter means. "Undo what this player did in the last hour" is not one
 * destination — the diamonds go back in the chest they came out of, the cobble goes back to the
 * coordinate it was mined from — and collapsing that into a single holder would turn a rollback
 * into a very tidy theft.
 */
public sealed interface RollbackTarget {
    public data class Uniform(public val holder: HolderId) : RollbackTarget

    public data class PerRoot(public val byRoot: Map<LotId, HolderId>) : RollbackTarget
}
