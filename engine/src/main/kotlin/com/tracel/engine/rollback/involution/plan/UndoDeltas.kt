package com.tracel.engine.rollback.involution.plan

import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.model.lot.LotId

/** Net physical change per real holder undoing [steps] implies. */
public fun physicalDeltasForUndo(
    steps: List<InvolutionStep>,
    noise: Set<LotId>? = null
): Map<HolderId, Map<ItemKey, Long>> {
    val deltas = mutableMapOf<HolderId, MutableMap<ItemKey, Long>>()

    /** Add the given amount of the given item key to the given holder. */
    fun add(holder: HolderId, itemKey: ItemKey, amount: Long) {
        deltas.getOrPut(holder) { mutableMapOf() }.merge(itemKey, amount, Long::plus)
    }

    val returned = HashMap<Pair<HolderId, ItemKey>, Long>()
    for (step in steps) if (step is InvolutionStep.Return) returned.merge(
        step.from to step.itemKey,
        step.quantity.raw,
        Long::plus
    )

    for (step in steps) {
        when (step) {
            is InvolutionStep.Return -> {
                add(step.from, step.itemKey, -step.quantity.raw)
                add(step.to, step.itemKey, step.quantity.raw)
            }

            is InvolutionStep.Retract -> {
                val account = step.from to step.itemKey
                val coverable = noise == null || step.originalLot in noise
                val covered = if (!coverable) 0L else minOf(returned[account] ?: 0L, step.quantity.raw)
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
