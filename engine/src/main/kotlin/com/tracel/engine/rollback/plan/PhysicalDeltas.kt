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

        for (step in plan.steps) {
            when (step) {
                is RollbackStep.Take -> {
                    val key = ledger.itemKeyOf(step.lotId)
                    add(step.holder, key, -step.quantity.raw)
                    add(destinationFor(step.lotId), key, step.quantity.raw)
                }

                is RollbackStep.Mint -> add(destinationFor(step.lotId), ledger.itemKeyOf(step.lotId), step.quantity.raw)
                is RollbackStep.Debt -> add(destinationFor(step.lotId), ledger.itemKeyOf(step.lotId), step.quantity.raw)

                is RollbackStep.Unmake -> {
                    add(step.holder, ledger.itemKeyOf(step.outputLot), -ledger.quantityOf(step.outputLot).raw)
                    for (input in step.inputs) add(step.holder, ledger.itemKeyOf(input.lotId), input.quantity.raw)
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

    for (step in steps) {
        when (step) {
            is InvolutionStep.Return -> {
                add(step.from, step.itemKey, -step.quantity.raw)
                add(step.to, step.itemKey, step.quantity.raw)
            }

            is InvolutionStep.Retract -> add(step.from, step.itemKey, -step.quantity.raw)

            is InvolutionStep.Remake -> {
                for ((holder, itemKey, quantity) in step.ingredients) add(holder, itemKey, -quantity.raw)
                add(step.product.holder, step.product.itemKey, step.product.quantity.raw)
            }
        }
    }
    return deltas
}
