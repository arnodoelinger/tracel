package com.tracel.engine.rollback.plan

import com.tracel.engine.ledger.LotLedger
import com.tracel.model.holder.HolderId
import com.tracel.model.id.LotId
import com.tracel.model.item.ItemKey

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

/**
 * Where [lotId]'s material ends up under this target.
 *
 * A leaf lot does not know where its material started; [RollbackPlan.rootOf] does, which is why
 * the lookup goes through the root rather than the lot in hand.
 */
public fun RollbackTarget.destinationFor(plan: RollbackPlan, lotId: LotId): HolderId = when (this) {
    is RollbackTarget.Uniform -> holder
    is RollbackTarget.PerRoot -> {
        val root = plan.rootOf[lotId] ?: lotId
        byRoot[root] ?: error("no destination for lot $lotId (root $root) — the plan and the target disagree")
    }
}

/** Every holder this plan delivers to, and how much of what. Gross deliveries, not net movement. */
public suspend fun escrowDeliveries(
    plan: RollbackPlan,
    target: RollbackTarget,
    ledger: LotLedger,
): Map<HolderId, Map<ItemKey, Long>> = ledger.atomically {
    val out = mutableMapOf<HolderId, MutableMap<ItemKey, Long>>()
    for (step in plan.steps) {
        val lotId = when (step) {
            is RollbackStep.Take -> step.lotId
            is RollbackStep.Mint -> step.lotId
            is RollbackStep.Debt -> step.lotId

            // An unmake happens where the craft was; nothing of it passes through escrow
            is RollbackStep.Unmake -> continue
        }
        val quantity = when (step) {
            is RollbackStep.Take -> step.quantity
            is RollbackStep.Mint -> step.quantity
            is RollbackStep.Debt -> step.quantity
            is RollbackStep.Unmake -> continue
        }
        out.getOrPut(target.destinationFor(plan, lotId)) { mutableMapOf() }
            .merge(ledger.itemKeyOf(lotId), quantity.raw, Long::plus)
    }
    out
}
