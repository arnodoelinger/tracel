package com.tracel.plugin.rollback.composer

import com.tracel.plugin.util.blockPos
import com.tracel.engine.rollback.plan.RollbackTarget
import com.tracel.plugin.rollback.structure.redstone.redstoneCells
import com.tracel.engine.rollback.involution.InvolutionOutcome
import com.tracel.engine.rollback.involution.InvolutionPlanner
import com.tracel.engine.rollback.job.RollbackJobRecord
import com.tracel.engine.rollback.plan.noiseMints
import com.tracel.engine.rollback.job.RollbackJobRepository
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.engine.rollback.structure.inverse
import com.tracel.model.holder.HolderId
import com.tracel.model.id.RollbackJobId
import com.tracel.model.item.ItemKey
import com.tracel.plugin.rollback.result.outcome.Blocked
import com.tracel.plugin.rollback.result.outcome.PreflightResult
import com.tracel.plugin.rollback.result.report.RestorationReport
import com.tracel.plugin.rollback.result.outcome.UndoResult
import com.tracel.plugin.rollback.result.outcome.Unreachable

import com.tracel.plugin.rollback.structure.StructurePass
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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

    val putBack = record.destroy.map { it.inverse() }
    val takeAway = record.create.map { it.inverse() }

    val materialSteps = InvolutionPlanner(services.repo).plan(record)

    when (val preflight = structureHalf.preflight(putBack + takeAway)) {
        is Unreachable -> return preflight
        PreflightResult.Ok -> Unit
    }
    val noise = record.plan.noiseMints()
    val undoDeltas = materialHalf.deltasForUndo(materialSteps, noise)
    when (val preflight = materialHalf.preflight(undoDeltas)) {
        is Unreachable -> return preflight
        PreflightResult.Ok -> Unit
    }

    return services.frozen.whileFrozen(undoDeltas.keys) { undoFrozen(job, record, putBack, takeAway, undoDeltas) }
}

private suspend fun RollbackComposer.undoFrozen(
    job: RollbackJobId,
    record: RollbackJobRecord,
    putBack: List<StructureStep>,
    takeAway: List<StructureStep>,
    undoDeltas: Map<HolderId, Map<ItemKey, Long>>,
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
            structureHalf.settleFluids(inverted.applied, drain = false)
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
            val material = materialHalf.undoRestore(outcome.steps, job, record.plan.noiseMints(), asOf = record.executedAtMillis)
            services.undoJournal.markCompleted(job, MATERIAL_RETURNED)
            if (record.executedAtMillis > 0L) materialHalf.rewearUndo(outcome.steps, record.executedAtMillis)
            val removed = structureHalf.restore(takeAway, takingAway)
            structureHalf.settleFluids(restored.applied + removed.applied, drain = false)

            // Return-to-vanished-drop from an entity hull
            val hullAt = HashMap<UUID, HolderId>()
            for (step in takeAway) {
                if (step is StructureStep.RemoveEntity) {
                    hullAt[step.entity] = HolderId.Block(step.at.world, step.at.x, step.at.y, step.at.z)
                }
            }
            val respawned = materialHalf.respawnReturnedDrops(outcome.steps, job, hullAt)
            services.jobs.markUndone(job)

            // Physics was off; wake redstone after both halves. Nothing in the report waits on it
            val waking = (restored.applied + removed.applied).redstoneCells().toList()
            if (waking.isNotEmpty()) services.scope.launch { structureHalf.wakeRedstone(waking.asSequence()) }
            UndoResult.Done(
                job,
                restored + removed,
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
            structureHalf.settleFluids(restored.applied + removed.applied, drain = false)
            services.jobs.markUndone(job)
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

private fun RollbackJobRecord.touches(): Set<Any> = buildSet {
    for (step in create + destroy) add(step.at)
    for (holder in plan.holders) add(holder.blockPos() ?: holder)
    when (val target = target) {
        is RollbackTarget.Uniform -> add(target.holder.blockPos() ?: target.holder)
        is RollbackTarget.PerRoot -> for (holder in target.byRoot.values) add(holder.blockPos() ?: holder)
    }
}
