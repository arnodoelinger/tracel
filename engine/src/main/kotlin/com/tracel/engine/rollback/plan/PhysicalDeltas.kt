package com.tracel.engine.rollback.plan

import com.tracel.engine.ledger.LotLedger
import com.tracel.engine.rollback.plan.step.RollbackStep
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.item.ItemKey
import com.tracel.model.lot.LotId

/**
 * Net physical change per real holder a [plan] (applied with [restoreTo] as the final
 * destination) implies — independent of re-reading the transaction log, since [plan] plus
 * [restoreTo] already carries everything needed.
 *
 * A holder that is both a source and [restoreTo] (rolling back to yourself) nets to zero via the
 * merge.
 */
public suspend fun physicalDeltas(
    plan: RollbackPlan,
    restoreTo: HolderId,
    ledger: LotLedger
): Map<HolderId, Map<ItemKey, Long>> =
    physicalDeltas(plan, RollbackTarget.Uniform(restoreTo), ledger)

/** The same, for a rollback whose reclaimed material does not all go to one place. */
public suspend fun physicalDeltas(
    plan: RollbackPlan,
    target: RollbackTarget,
    ledger: LotLedger
): Map<HolderId, Map<ItemKey, Long>> =
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

        // A run's lots share one item and, mostly, one destination: summed per destination
        fun RollbackStep.TakeRun.perDestination(): Map<HolderId, Long> {
            val out = LinkedHashMap<HolderId, Long>()
            for (k in 0 until size) out.merge(destinationFor(lotAt(k)), quantities[k], Long::plus)
            return out
        }

        val runs = HashMap<RollbackStep.TakeRun, Pair<ItemKey, Map<HolderId, Long>>>()
        for (step in plan.steps) {
            if (step is RollbackStep.Take) takenAdd(
                destinationFor(step.lotId),
                ledger.itemKeyOf(step.lotId),
                step.quantity.raw
            )
            if (step is RollbackStep.TakeRun) {
                val run = ledger.itemKeyOf(step.lotAt(0)) to step.perDestination()
                runs[step] = run
                for ((dest, quantity) in run.second) takenAdd(dest, run.first, quantity)
            }
        }

        for (step in plan.steps) {
            when (step) {
                is RollbackStep.Take -> {
                    val key = ledger.itemKeyOf(step.lotId)
                    add(step.holder, key, -step.quantity.raw)
                    add(destinationFor(step.lotId), key, step.quantity.raw)
                }

                is RollbackStep.TakeRun -> {
                    val (key, byDest) = runs.getValue(step)
                    for ((dest, quantity) in byDest) {
                        add(step.holder, key, -quantity)
                        add(dest, key, quantity)
                    }
                }

                is RollbackStep.Mint -> {
                    val key = ledger.itemKeyOf(step.lotId)
                    val dest = destinationFor(step.lotId)
                    val covered = if (step.reason != SinkKind.UNATTRIBUTED) 0L else minOf(
                        taken[dest]?.get(key) ?: 0L,
                        step.quantity.raw
                    )
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

/** Mints the rollback folded into a delivery of the same key: the ones an undo may fold back. */
public fun RollbackPlan.noiseMints(): Set<LotId> =
    steps.mapNotNullTo(HashSet()) { step -> (step as? RollbackStep.Mint)?.takeIf { it.reason == SinkKind.UNATTRIBUTED }?.lotId }
