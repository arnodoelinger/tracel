package com.tracel.engine.rollback.involution

import com.tracel.engine.ledger.Ingredient
import com.tracel.engine.ledger.LotRepository
import com.tracel.engine.ledger.Product
import com.tracel.engine.rollback.RollbackJobRecord
import com.tracel.engine.rollback.RollbackStep
import com.tracel.model.holder.HolderId

/**
 * Builds the [InvolutionStep]s that reverse an already-applied [RollbackJobRecord], in the
 * opposite order it was applied: [RollbackJobRecord.plan]'s [RollbackStep.Unmake]s always run
 * before the steps that depend on their output existing again, so undoing has to put the
 * give-backs and burns first and re-craft last.
 */
public class InvolutionPlanner(private val repo: LotRepository) {
    public suspend fun plan(job: RollbackJobRecord): List<InvolutionStep> = repo.atomically {
        job.plan.steps.asReversed().map { stepFor(it, job.restoreTo) }
    }

    private suspend fun stepFor(step: RollbackStep, restoreTo: HolderId): InvolutionStep = when (step) {
        is RollbackStep.Take ->
            InvolutionStep.Return(repo.lot(step.lotId).itemKey, step.quantity, restoreTo, step.holder)

        is RollbackStep.Mint ->
            InvolutionStep.Retract(repo.lot(step.lotId).itemKey, step.quantity, restoreTo)

        is RollbackStep.Debt ->
            InvolutionStep.Retract(repo.lot(step.lotId).itemKey, step.quantity, restoreTo)

        is RollbackStep.Unmake -> {
            val output = repo.lot(step.outputLot)
            InvolutionStep.Remake(
                ingredients = step.inputs.map { Ingredient(step.holder, repo.lot(it.lotId).itemKey, it.quantity) },
                product = Product(step.holder, output.itemKey, output.quantity),
            )
        }
    }
}
