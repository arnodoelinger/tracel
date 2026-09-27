package com.tracel.engine.rollback.involution

import com.tracel.annotations.RunsOn
import com.tracel.annotations.ThreadContext
import com.tracel.engine.ledger.LotRepository
import com.tracel.engine.ledger.Product
import com.tracel.engine.rollback.job.RollbackJobRecord
import com.tracel.engine.rollback.plan.RollbackStep
import com.tracel.engine.rollback.plan.destinationFor
import com.tracel.model.holder.HolderId
import com.tracel.model.id.LotId
import com.tracel.model.id.Quantity
import com.tracel.model.item.ItemKey
import com.tracel.model.lot.LotEdge

/**
 * Builds the [InvolutionStep]s that reverse an already-applied [RollbackJobRecord], in the
 * opposite order it was applied: [RollbackJobRecord.plan]'s [RollbackStep.Unmake]s always run
 * before the steps that depend on their output existing again, so undoing has to put the
 * give-backs and burns first and re-craft last.
 */
@RunsOn(ThreadContext.ASYNC)
public class InvolutionPlanner(private val repo: LotRepository) {
    /** A holder that vanished since needs nothing special: its lots burned when it did, so nothing is payable off it. */
    public suspend fun plan(job: RollbackJobRecord, resuming: Boolean = false): List<InvolutionStep> = repo.reading {
        val books = Books(repo, byLot = !resuming)
        val steps = job.plan.steps.flatMap { if (it is RollbackStep.TakeRun) it.takes() else listOf(it) }
        steps.asReversed().mapNotNull { step ->
            val delivered = when (step) {
                is RollbackStep.TakeRun -> error("runs were expanded above")
                is RollbackStep.Take -> job.target.destinationFor(job.plan, step.lotId)
                is RollbackStep.Mint -> job.target.destinationFor(job.plan, step.lotId)
                is RollbackStep.Debt -> job.target.destinationFor(job.plan, step.lotId)

                // An unmake never delivered anywhere; the holder in the step is where it happened
                is RollbackStep.Unmake -> step.holder
            }
            stepFor(books, job, step, delivered)
        }
    }

    /**
     * Only reverse what is still sitting at [restoreTo]. A dest that has since been emptied
     * (hopper, vanished drop credited to a PlacedBlock, entity gone) is not a reason to abort
     * the whole undo — take what is there, skip the rest.
     */
    private suspend fun stepFor(
        books: Books,
        job: RollbackJobRecord,
        step: RollbackStep,
        restoreTo: HolderId
    ): InvolutionStep? = when (step) {
        is RollbackStep.TakeRun -> error("runs are expanded before they get here")
        is RollbackStep.Take -> {
            val itemKey = repo.lot(step.lotId).itemKey
            books.payable(restoreTo, itemKey, step.quantity, step.lotId)?.let { qty ->
                books.debit(restoreTo, itemKey, qty.raw)
                books.tookFrom(restoreTo, step.lotId, qty.raw)
                books.credit(step.holder, itemKey, qty.raw)
                InvolutionStep.Return(itemKey, qty, restoreTo, step.holder, step.lotId)
            }
        }

        is RollbackStep.Mint -> retract(books, job, step.lotId, step.quantity, restoreTo)

        is RollbackStep.Debt -> retract(books, job, step.lotId, step.quantity, restoreTo)

        is RollbackStep.Unmake -> {
            val wanted = LinkedHashMap<ItemKey, Long>()
            for ((lotId, quantity) in step.inputs) wanted.merge(repo.lot(lotId).itemKey, quantity.raw, Long::plus)

            if (wanted.any { (itemKey, amount) -> books.available(step.holder, itemKey) < amount }) {
                null
            } else {
                val outputs = step.outputs.map { RemakeOutput(it.lotId, repo.lot(it.lotId).quantity, it.holder) }
                val first = repo.lot(outputs.first().lotId)
                val whole = Quantity(outputs.sumOf { it.quantity.raw })
                for ((itemKey, amount) in wanted) books.debit(step.holder, itemKey, amount)
                books.credit(step.holder, first.itemKey, whole.raw)
                InvolutionStep.Remake(
                    outputs,
                    Product(step.holder, first.itemKey, whole),
                    step.inputs.map { RemakeInput(it.lotId, repo.lot(it.lotId).itemKey, it.quantity) },
                )
            }
        }
    }

    /** Undoing a compensation, with the lot the compensation actually minted. */
    private suspend fun retract(
        books: Books,
        job: RollbackJobRecord,
        originalLot: LotId,
        quantity: Quantity,
        restoreTo: HolderId,
    ): InvolutionStep? {
        val itemKey = repo.lot(originalLot).itemKey
        val minted = repo.findCompensateEdge(originalLot, job.id)?.child
        val qty = books.payable(restoreTo, itemKey, quantity, minted) ?: return null
        books.debit(restoreTo, itemKey, qty.raw)
        books.tookFrom(restoreTo, minted, qty.raw)
        return InvolutionStep.Retract(itemKey, qty, restoreTo, originalLot, minted)
    }
}

/**
 * What an account will hold by the time a step actually runs, counting what the steps above it
 * are about to put there.
 */
/**
 * [byLot]: pay only out of the job's own lot and its pieces. Off when resuming a journal that already
 * counted steps, whose indices have to name the same steps the first attempt planned.
 */
private class Books(private val repo: LotRepository, private val byLot: Boolean) {
    private val actual = HashMap<Pair<HolderId, ItemKey>, Long>()
    private val incoming = HashMap<Pair<HolderId, ItemKey>, Long>()

    suspend fun available(holder: HolderId, itemKey: ItemKey): Long {
        val account = holder to itemKey
        val have = actual.getOrPut(account) { repo.totalOf(holder, itemKey) }
        return have + (incoming[account] ?: 0L)
    }

    fun credit(holder: HolderId, itemKey: ItemKey, amount: Long) {
        incoming.merge(holder to itemKey, amount, Long::plus)
    }

    private val taken = HashMap<Pair<HolderId, LotId>, Long>()

    private suspend fun heldOf(holder: HolderId, lot: LotId): Long {
        var sum = 0L
        val frontier = ArrayDeque(listOf(lot))
        val seen = HashSet<LotId>()
        while (frontier.isNotEmpty()) {
            val id = frontier.removeFirst()
            if (!seen.add(id)) continue
            val placed = repo.placementOf(holder, id)?.remaining?.raw
            if (placed != null) sum += placed
            else for (edge in repo.edgesFrom(id)) if (edge is LotEdge.Split) frontier += edge.child
        }
        return sum - (taken[holder to lot] ?: 0L)
    }

    fun tookFrom(holder: HolderId, lot: LotId?, amount: Long) {
        if (lot != null) taken.merge(holder to lot, amount, Long::plus)
    }

    fun debit(holder: HolderId, itemKey: ItemKey, amount: Long) {
        if (byLot) incoming.merge(holder to itemKey, -amount, Long::plus)
    }

    suspend fun payable(holder: HolderId, itemKey: ItemKey, wanted: Quantity, lot: LotId?): Quantity? {
        val have = if (holder is HolderId.Source || holder is HolderId.Sink) {
            lot?.let { repo.placementOf(holder, it)?.remaining?.raw } ?: 0L
        } else {
            val held = if (byLot) lot?.let { heldOf(holder, it) } else null
            if (held == null) available(holder, itemKey) else minOf(available(holder, itemKey), held)
        }
        if (have <= 0L) return null
        return if (have >= wanted.raw) wanted else Quantity(have)
    }
}
