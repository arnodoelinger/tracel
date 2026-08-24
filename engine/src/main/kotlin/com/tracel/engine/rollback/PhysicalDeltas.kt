package com.tracel.engine.rollback

import com.tracel.engine.ledger.LotLedger
import com.tracel.engine.rollback.involution.InvolutionStep
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey

/**
 * Net physical change per real holder a [plan] (applied with [restoreTo] as the final
 * destination) implies — independent of re-reading the transaction log, since [plan] plus
 * [restoreTo] already carries everything needed.
 *
 * A holder that is both a source and [restoreTo] (rolling back to yourself) nets to zero via the
 * merge, no special-casing needed.
 */
public fun physicalDeltas(plan: RollbackPlan, restoreTo: HolderId, ledger: LotLedger): Map<HolderId, Map<ItemKey, Long>> {
    val deltas = mutableMapOf<HolderId, MutableMap<ItemKey, Long>>()
    fun add(holder: HolderId, itemKey: ItemKey, amount: Long) {
        deltas.getOrPut(holder) { mutableMapOf() }.merge(itemKey, amount, Long::plus)
    }

    for (step in plan.steps) {
        when (step) {
            is RollbackStep.Take -> {
                val key = ledger.itemKeyOf(step.lotId)
                add(step.holder, key, -step.quantity.raw)
                add(restoreTo, key, step.quantity.raw)
            }

            is RollbackStep.Mint -> add(restoreTo, ledger.itemKeyOf(step.lotId), step.quantity.raw)
            is RollbackStep.Debt -> add(restoreTo, ledger.itemKeyOf(step.lotId), step.quantity.raw)

            is RollbackStep.Unmake -> {
                add(step.holder, ledger.itemKeyOf(step.outputLot), -ledger.quantityOf(step.outputLot).raw)
                for (input in step.inputs) add(step.holder, ledger.itemKeyOf(input.lotId), input.quantity.raw)
            }
        }
    }

    return deltas
}

/**
 * Net physical change per real holder undoing [steps] implies — the involution-side mirror of
 * [physicalDeltas]. Needs no [LotLedger] lookup, unlike the forward direction: [InvolutionStep]
 * already carries its own item key and quantity directly, since it is deliberately expressed in
 * those terms rather than lot ids.
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
