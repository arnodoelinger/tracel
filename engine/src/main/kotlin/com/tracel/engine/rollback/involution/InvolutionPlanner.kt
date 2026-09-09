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

/**
 * Builds the [InvolutionStep]s that reverse an already-applied [RollbackJobRecord], in the
 * opposite order it was applied: [RollbackJobRecord.plan]'s [RollbackStep.Unmake]s always run
 * before the steps that depend on their output existing again, so undoing has to put the
 * give-backs and burns first and re-craft last.
 */
@RunsOn(ThreadContext.ASYNC)
public class InvolutionPlanner(private val repo: LotRepository) {
    /** [vanished] is every [RollbackStep.Take] holder the world no longer has. */
    @Suppress("UNUSED_PARAMETER")
    public suspend fun plan(job: RollbackJobRecord, vanished: Set<HolderId> = emptySet()): List<InvolutionStep> = repo.reading {
        val books = Books(repo)
        job.plan.steps.asReversed().mapNotNull { step ->
            val delivered = when (step) {
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
    private suspend fun stepFor(books: Books, job: RollbackJobRecord, step: RollbackStep, restoreTo: HolderId): InvolutionStep? = when (step) {
        is RollbackStep.Take -> {
            val itemKey = repo.lot(step.lotId).itemKey
            books.payable(restoreTo, itemKey, step.quantity)?.let { qty ->
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
        val qty = books.payable(restoreTo, itemKey, quantity) ?: return null
        val minted = repo.findCompensateEdge(originalLot, job.id)?.child
        return InvolutionStep.Retract(itemKey, qty, restoreTo, originalLot, minted)
    }
}

/**
 * What an account will hold by the time a step actually runs, counting what the steps above it
 * are about to put there.
 */
private class Books(private val repo: LotRepository) {
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

    suspend fun payable(holder: HolderId, itemKey: ItemKey, wanted: Quantity): Quantity? {
        val have = available(holder, itemKey)
        if (have <= 0L) return null
        return if (have >= wanted.raw) wanted else Quantity(have)
    }
}
