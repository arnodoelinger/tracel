package com.tracel.engine.rollback.plan

import com.tracel.engine.ledger.LotLedger
import com.tracel.engine.rollback.involution.InvolutionStep
import com.tracel.model.holder.HolderId
import com.tracel.model.id.LotId
import com.tracel.model.item.ItemKey

/**
 * Net physical change per real holder a [plan] (applied with [restoreTo] as the final
 * destination) implies — independent of re-reading the transaction log, since [plan] plus
 * [restoreTo] already carries everything needed.
 *
 * A holder that is both a source and [restoreTo] (rolling back to yourself) nets to zero via the
 * merge.
 */
public suspend fun physicalDeltas(plan: RollbackPlan, restoreTo: HolderId, ledger: LotLedger): Map<HolderId, Map<ItemKey, Long>> =
    physicalDeltas(plan, RollbackTarget.Uniform(restoreTo), ledger)

/** The same, for a rollback whose reclaimed material does not all go to one place. */
public suspend fun physicalDeltas(plan: RollbackPlan, target: RollbackTarget, ledger: LotLedger): Map<HolderId, Map<ItemKey, Long>> =
    ledger.reading {
        val deltas = mutableMapOf<HolderId, MutableMap<ItemKey, Long>>()
        fun add(holder: HolderId, itemKey: ItemKey, amount: Long) {
            deltas.getOrPut(holder) { mutableMapOf() }.merge(itemKey, amount, Long::plus)
        }

        fun destinationFor(lotId: LotId): HolderId = target.destinationFor(plan, lotId)

        val taken = mutableMapOf<HolderId, MutableMap<ItemKey, Long>>()
        fun takenAdd(holder: HolderId, itemKey: ItemKey, amount: Long) {
            taken.getOrPut(holder) { mutableMapOf() }.merge(itemKey, amount, Long::plus)
        }

        for (step in plan.steps) {
            if (step is RollbackStep.Take) takenAdd(destinationFor(step.lotId), ledger.itemKeyOf(step.lotId), step.quantity.raw)
        }

        for (step in plan.steps) {
            when (step) {
                is RollbackStep.Take -> {
                    val key = ledger.itemKeyOf(step.lotId)
                    add(step.holder, key, -step.quantity.raw)
                    add(destinationFor(step.lotId), key, step.quantity.raw)
                }

                is RollbackStep.Mint -> {
                    val key = ledger.itemKeyOf(step.lotId)
                    val dest = destinationFor(step.lotId)
                    val covered = minOf(taken[dest]?.get(key) ?: 0L, step.quantity.raw)
                    if (covered > 0L) takenAdd(dest, key, -covered)
                    if (step.quantity.raw > covered) add(dest, key, step.quantity.raw - covered)
                }
                is RollbackStep.Debt -> add(destinationFor(step.lotId), ledger.itemKeyOf(step.lotId), step.quantity.raw)

                is RollbackStep.Unmake -> {
                    for ((lotId, holder) in step.outputs) {
                        add(holder, ledger.itemKeyOf(lotId), -ledger.quantityOf(lotId).raw)
                    }
                    for ((lotId, quantity) in step.inputs) add(step.holder, ledger.itemKeyOf(lotId), quantity.raw)
                }
            }
        }

        deltas
    }

/**
 * Net physical change per real holder undoing [steps] implies — the involution-side mirror of
 * [physicalDeltas].
 */
public fun physicalDeltasForUndo(steps: List<InvolutionStep>): Map<HolderId, Map<ItemKey, Long>> {
    val deltas = mutableMapOf<HolderId, MutableMap<ItemKey, Long>>()
    fun add(holder: HolderId, itemKey: ItemKey, amount: Long) {
        deltas.getOrPut(holder) { mutableMapOf() }.merge(itemKey, amount, Long::plus)
    }

    val returned = HashMap<Pair<HolderId, ItemKey>, Long>()
    for (step in steps) if (step is InvolutionStep.Return) returned.merge(step.from to step.itemKey, step.quantity.raw, Long::plus)

    for (step in steps) {
        when (step) {
            is InvolutionStep.Return -> {
                add(step.from, step.itemKey, -step.quantity.raw)
                add(step.to, step.itemKey, step.quantity.raw)
            }

            is InvolutionStep.Retract -> {
                val account = step.from to step.itemKey
                val covered = minOf(returned[account] ?: 0L, step.quantity.raw)
                if (covered > 0L) returned[account] = returned.getValue(account) - covered
                if (step.quantity.raw > covered) add(step.from, step.itemKey, covered - step.quantity.raw)
            }

            is InvolutionStep.Remake -> {
                for ((_, itemKey, quantity) in step.inputs) add(step.product.holder, itemKey, -quantity.raw)
                // Back to the holders the unmake took the pieces off, which for a split output
                // need not be the one the ingredients came from.
                for ((_, quantity, holder) in step.outputs) add(holder, step.product.itemKey, quantity.raw)
            }
        }
    }
    return deltas
}
