package com.tracel.engine.rollback.involution

import com.tracel.annotations.RunsOn
import com.tracel.annotations.ThreadContext
import com.tracel.engine.ledger.Ingredient
import com.tracel.engine.ledger.LotRepository
import com.tracel.engine.ledger.Product
import com.tracel.engine.rollback.job.RollbackJobRecord
import com.tracel.engine.rollback.plan.RollbackStep
import com.tracel.engine.rollback.plan.destinationFor
import com.tracel.model.holder.HolderId

/**
 * Builds the [InvolutionStep]s that reverse an already-applied [RollbackJobRecord], in the
 * opposite order it was applied: [RollbackJobRecord.plan]'s [RollbackStep.Unmake]s always run
 * before the steps that depend on their output existing again, so undoing has to put the
 * give-backs and burns first and re-craft last.
 */
@RunsOn(ThreadContext.ASYNC)
public class InvolutionPlanner(private val repo: LotRepository) {
    /**
     * [vanished] is every [RollbackStep.Take] holder the world no longer has — the ground item a
     * rollback consumed down to nothing, the frame that got broken again since. Undoing a `Take`
     * means moving material back into that holder, and a holder that is not there anymore is
     * not somewhere a rollback can leave anything.
     */
    public suspend fun plan(job: RollbackJobRecord, vanished: Set<HolderId> = emptySet()): List<InvolutionStep> = repo.reading {
        job.plan.steps.asReversed().mapNotNull { step ->
            if (step is RollbackStep.Take && step.holder in vanished) return@mapNotNull null
            val delivered = when (step) {
                is RollbackStep.Take -> job.target.destinationFor(job.plan, step.lotId)
                is RollbackStep.Mint -> job.target.destinationFor(job.plan, step.lotId)
                is RollbackStep.Debt -> job.target.destinationFor(job.plan, step.lotId)

                // An unmake never delivered anywhere; the holder in the step is where it happened
                is RollbackStep.Unmake -> step.holder
            }
            stepFor(step, delivered)
        }
    }

    private suspend fun stepFor(step: RollbackStep, restoreTo: HolderId): InvolutionStep = when (step) {
        is RollbackStep.Take ->
            InvolutionStep.Return(repo.lot(step.lotId).itemKey, step.quantity, restoreTo, step.holder)

        is RollbackStep.Mint ->
            InvolutionStep.Retract(repo.lot(step.lotId).itemKey, step.quantity, restoreTo, step.lotId)

        is RollbackStep.Debt ->
            InvolutionStep.Retract(repo.lot(step.lotId).itemKey, step.quantity, restoreTo, step.lotId)

        is RollbackStep.Unmake -> {
            val output = repo.lot(step.outputLot)
            InvolutionStep.Remake(
                ingredients = step.inputs.map { Ingredient(step.holder, repo.lot(it.lotId).itemKey, it.quantity) },
                product = Product(step.holder, output.itemKey, output.quantity),
            )
        }
    }
}
