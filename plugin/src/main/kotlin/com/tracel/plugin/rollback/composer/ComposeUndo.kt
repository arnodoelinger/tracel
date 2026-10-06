package com.tracel.plugin.rollback.composer

import com.tracel.engine.rollback.involution.InvolutionOutcome
import com.tracel.engine.rollback.involution.plan.InvolutionPlanner
import com.tracel.engine.rollback.involution.plan.InvolutionStep
import com.tracel.engine.rollback.job.record.RollbackJobRecord
import com.tracel.engine.rollback.job.record.RollbackJobRepository
import com.tracel.engine.rollback.plan.RollbackTarget
import com.tracel.engine.rollback.plan.noiseMints
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.engine.rollback.structure.inverse
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.model.rollback.RollbackJobId
import com.tracel.plugin.rollback.result.outcome.Blocked
import com.tracel.plugin.rollback.result.outcome.PreflightResult
import com.tracel.plugin.rollback.result.outcome.UndoResult
import com.tracel.plugin.rollback.result.outcome.Unreachable
import com.tracel.plugin.rollback.result.report.RestorationReport
import com.tracel.plugin.rollback.result.report.SkippedStep
import com.tracel.plugin.rollback.result.report.StructureReport
import com.tracel.plugin.rollback.structure.StructurePass
import com.tracel.plugin.rollback.structure.redstone.redstoneCells
import com.tracel.plugin.util.blockPos
import com.tracel.plugin.util.isAirLike
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.*
import kotlin.coroutines.cancellation.CancellationException

internal const val MAX_STACKED_JOBS = RollbackJobRepository.UNDO_DEPTH

private const val MATERIAL_RETURNED = Int.MAX_VALUE

internal suspend fun RollbackComposer.undoTracked(job: RollbackJobId): UndoResult {
    val record = services.jobs.find(job) ?: return UndoResult.NotFound
    if (!services.jobs.isUndoable(job)) return UndoResult.AlreadyUndone

    val mine = record.touches()
    val newer = services.jobs.undoable(limit = MAX_STACKED_JOBS * MAX_STACKED_JOBS)
        .filter { it.raw > job.raw }
        .filter { other -> services.jobs.find(other)?.touches()?.any { it in mine } ?: false }
    if (newer.isNotEmpty()) return UndoResult.OutOfOrder(job, newer)

    val takeAway = record.create.map { it.inverse() }

    val materialSteps = InvolutionPlanner(services.repo).plan(record)

    // A placed hull the ledger cannot give its item back to (the player used it since) stays gone: bringing it back
    // beside the item would be a copy
    val itemBack = materialSteps.mapNotNullTo(HashSet()) { (it as? InvolutionStep.Return)?.to }
    val (putBack, unbacked) = record.destroy.map { it.inverse() }.partition { step ->
        val placed = when (step) {
            is StructureStep.SpawnEntity -> HolderId.PlacedEntity(step.entity)
            is StructureStep.SetBlock ->
                if (step.target.isAirLike) null else HolderId.PlacedBlock(
                    step.at.world,
                    step.at.x,
                    step.at.y,
                    step.at.z
                )

            else -> null
        }
        placed == null || placed !in record.plan.holders || placed in itemBack
    }
    val notBrought = StructureReport(
        emptyList(),
        unbacked.map {
            SkippedStep(
                it.at,
                "its item is no longer where the rollback left it, so it was not brought back"
            )
        },
    )

    when (val preflight = structureHalf.preflight(putBack + takeAway)) {
        is Unreachable -> return preflight
        PreflightResult.Ok -> Unit
    }
    val noise = record.plan.noiseMints()
    val undoDeltas = materialHalf.deltasForUndo(materialSteps, noise)

    // Taking a hull or container away that still holds items would destroy them, or leave both it and its item.
    // Counted as the ledger will: folded noise mints leave the books too, not only the world.
    val bookDeltas = if (noise.isEmpty()) undoDeltas else materialHalf.deltasForUndo(materialSteps, emptySet())
    val stuck = services.leftHolding(takeAway, bookDeltas).firstOrNull()
    if (stuck != null) {
        return Unreachable(
            stuck,
            "what the rollback put back now holds items that were put in after it; take them out first",
        )
    }
    when (val preflight = materialHalf.preflight(undoDeltas)) {
        is Unreachable -> return preflight
        PreflightResult.Ok -> Unit
    }

    // Where a hull that does not come back last stood: what it was owed then lands on the ground there
    for (step in record.destroy.map { it.inverse() }) if (step is StructureStep.SpawnEntity) rememberHull(step)

    return services.frozen.whileFrozen(undoDeltas.keys) {
        // Fluids held still while the job is taken back, woken once it is
        val written = ArrayList<StructureStep>()
        val result = structureHalf.holdingFluids(putBack + takeAway) {
            undoFrozen(
                job,
                record,
                putBack,
                takeAway,
                undoDeltas,
                notBrought,
                written,
            )
        }
        structureHalf.settleFluids(written)
        result
    }
}

private suspend fun RollbackComposer.undoFrozen(
    job: RollbackJobId,
    record: RollbackJobRecord,
    putBack: List<StructureStep>,
    takeAway: List<StructureStep>,
    undoDeltas: Map<HolderId, Map<ItemKey, Long>>,
    notBrought: StructureReport,
    written: MutableList<StructureStep>,
): UndoResult {

    // Strip ledger-filled hulls; keep unbooked snapshot cargo or undo empties the creative frame
    val undoFedByLedger = filledByLedger(undoDeltas)
    val undoKeepCargoFor = putBack.asSequence()
        .filterIsInstance<StructureStep.SpawnEntity>()
        .map { it.entity }
        .filterNot { it in undoFedByLedger }
        .toHashSet()
    val undoLedgerCargoFor = emptiedByLedger(undoDeltas)

    // The ledger does not wait on the world: put-back and the books run side by side, like a forward apply
    val (restored, undone) = coroutineScope {
        val putting = async {
            structureHalf.restore(putBack, StructurePass(force = true, keepCargoFor = undoKeepCargoFor))
        }
        val ledger = async {
            try {
                Result.success(services.undo.undo(job))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Result.failure(failure)
            }
        }
        putting.await() to ledger.await()
    }

    // Structure already put back, so ledger may refuse. Inverse on Blocked / NotFound too, not only athrow
    suspend fun putBackAgain() {
        if (restored.applied.isNotEmpty()) {
            val inverted = structureHalf.restore(restored.applied.map { it.inverse() }, StructurePass(force = true))
            written += inverted.applied
        }
    }

    val outcome = undone.getOrElse { failure ->
        putBackAgain()
        return UndoResult.Failed(failure.message ?: failure.toString())
    }

    // What players put into a restored chest since is theirs: refuse the cell, never empty it into nothing
    val takingAway = StructurePass(force = true, dumpHeldCargo = false, ledgerCargoFor = undoLedgerCargoFor)

    return when (outcome) {
        is InvolutionOutcome.Undone -> {
            val material =
                materialHalf.undoRestore(outcome.steps, job, record.plan.noiseMints(), asOf = record.executedAtMillis)
            services.undoJournal.markCompleted(job, MATERIAL_RETURNED)
            if (record.executedAtMillis > 0L) materialHalf.rewearUndo(outcome.steps, record.executedAtMillis)
            val removed = structureHalf.restore(takeAway, takingAway)
            written += restored.applied + removed.applied

            // Return-to-vanished-drop from an entity hull
            val hullAt = HashMap<UUID, HolderId>()
            for (step in takeAway) {
                if (step is StructureStep.RemoveEntity) {
                    hullAt[step.entity] = HolderId.Block(step.at.world, step.at.x, step.at.y, step.at.z)
                }
            }
            val respawned = materialHalf.respawnReturnedDrops(outcome.steps, job, hullAt, material.shortfall)
            services.jobs.markUndone(job)
            services.rolledBack.restore(job.raw)

            // Physics was off; wake redstone after both halves. Nothing in the report waits on it
            val waking = (restored.applied + removed.applied).redstoneCells().toList()
            if (waking.isNotEmpty()) services.scope.launch { structureHalf.wakeRedstone(waking.asSequence()) }
            UndoResult.Done(
                job,
                restored + removed + notBrought,
                RestorationReport(
                    material.failures + respawned.failures,
                    material.queued + respawned.queued,
                    material.spilled + respawned.spilled,
                ),
                outcome.steps,
            )
        }

        // Ledger already undone, job still on the stack: finish takeAway
        is InvolutionOutcome.AlreadyUndone -> if (services.undoJournal.isCompleted(job, MATERIAL_RETURNED)) {
            val removed = structureHalf.restore(takeAway, takingAway)
            written += restored.applied + removed.applied
            services.jobs.markUndone(job)
            services.rolledBack.restore(job.raw)
            UndoResult.AlreadyUndone
        } else {
            putBackAgain()
            UndoResult.Failed(
                "an earlier undo of job ${job.raw} took the ledger back and stopped before putting items back; " +
                        "check the holders it touched by hand",
            )
        }

        is InvolutionOutcome.Blocked -> {
            putBackAgain()
            Blocked(outcome.conflicts)
        }

        InvolutionOutcome.NotFound -> {
            putBackAgain()
            UndoResult.NotFound
        }
    }
}

/** Records the position of an entity being spawned during the rollback process. */
internal fun RollbackComposer.rememberHull(step: StructureStep.SpawnEntity) {
    services.whereabouts.remember(step.entity, HolderId.Block(step.at.world, step.at.x, step.at.y, step.at.z))
}

private fun RollbackJobRecord.touches(): Set<Any> = buildSet {
    for (step in create + destroy) add(step.at)
    for (holder in plan.holders) touch(holder)
    when (val target = target) {
        is RollbackTarget.Uniform -> touch(target.holder)
        is RollbackTarget.PerRoot -> for (holder in target.byRoot.values) touch(holder)
    }
}

private fun MutableSet<Any>.touch(holder: HolderId) {
    if (holder is HolderId.Source || holder is HolderId.Sink || holder is HolderId.Escrow) return
    add(holder.blockPos() ?: holder)
}
