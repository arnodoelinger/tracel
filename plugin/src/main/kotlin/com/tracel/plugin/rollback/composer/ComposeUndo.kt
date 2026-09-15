package com.tracel.plugin.rollback.composer

import com.tracel.engine.rollback.involution.InvolutionOutcome
import com.tracel.engine.rollback.involution.InvolutionPlanner
import com.tracel.engine.rollback.job.RollbackJobRepository
import com.tracel.engine.rollback.plan.RollbackStep
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.engine.rollback.structure.inverse
import com.tracel.model.holder.HolderId
import com.tracel.model.id.RollbackJobId
import com.tracel.model.world.BlockPos
import com.tracel.plugin.rollback.result.outcome.Blocked
import com.tracel.plugin.rollback.result.outcome.PreflightResult
import com.tracel.plugin.rollback.result.report.RestorationReport
import com.tracel.plugin.rollback.result.outcome.UndoResult
import com.tracel.plugin.rollback.result.outcome.Unreachable
import com.tracel.plugin.rollback.structure.CargoPolicy
import com.tracel.plugin.rollback.structure.StructurePass
import com.tracel.plugin.util.entityUuid
import com.tracel.plugin.util.namedByEntity
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

internal const val MAX_STACKED_JOBS = RollbackJobRepository.UNDO_DEPTH

internal suspend fun RollbackComposer.undoTracked(job: RollbackJobId): UndoResult {
    val record = services.jobs.find(job) ?: return UndoResult.NotFound
    if (!services.jobs.isUndoable(job)) return UndoResult.AlreadyUndone

    // Undo is a stack. Reach past the top and newer jobs have already moved this material;
    // the take fails halfway and the world is half-undone.
    val newer = services.jobs.undoable(limit = MAX_STACKED_JOBS).filter { it.raw > job.raw }
    if (newer.isNotEmpty()) return UndoResult.OutOfOrder(job, newer)

    val putBack = record.destroy.map { it.inverse() }
    val takeAway = record.create.map { it.inverse() }

    // Vanished take dests -> spawn a new drop; exclude hulls we are about to spawn
    val comingBack = putBack.asSequence()
        .filterIsInstance<StructureStep.SpawnEntity>()
        .mapTo(HashSet()) { it.entity }
    val takeHolders = record.plan.steps.filterIsInstance<RollbackStep.Take>().map { it.holder }
    val candidates = takeHolders.filterTo(HashSet()) { it.namedByEntity() && it.entityUuid() !in comingBack }
    val vanished = if (candidates.isEmpty()) emptySet() else worldCensus.vanishedEntities(candidates)

    val materialSteps = InvolutionPlanner(services.repo).plan(record, vanished)

    when (val preflight = structureHalf.preflight(putBack + takeAway)) {
        is Unreachable -> return preflight
        PreflightResult.Ok -> Unit
    }
    val undoDeltas = materialHalf.deltasForUndo(materialSteps)
    when (val preflight = materialHalf.preflight(undoDeltas)) {
        is Unreachable -> return preflight
        PreflightResult.Ok -> Unit
    }

    // Strip ledger-filled hulls; keep unbooked snapshot cargo or undo empties the creative frame
    val undoFedByLedger = filledByLedger(undoDeltas)
    val undoKeepCargoFor = putBack.asSequence()
        .filterIsInstance<StructureStep.SpawnEntity>()
        .map { it.entity }
        .filterNot { it in undoFedByLedger }
        .toHashSet()
    val undoLedgerCargoFor = emptiedByLedger(undoDeltas)

    val restored = structureHalf.restore(putBack, StructurePass(force = true), CargoPolicy(keepCargoFor = undoKeepCargoFor))

    // Structure already put back, so ledger may refuse. Inverse on Blocked / NotFound too, not only athrow
    suspend fun putBackAgain() {
        if (restored.applied.isNotEmpty()) {
            structureHalf.restore(restored.applied.map { it.inverse() }, StructurePass(force = true))
        }
    }

    val outcome = try {
        services.undo.undo(job, vanished = vanished)
    } catch (cancelled: CancellationException) {
        // TODO: cancellation? nothing in this scope is alive to restore with, but still...
        throw cancelled
    } catch (failure: Throwable) {
        putBackAgain()
        return UndoResult.Failed(failure.message ?: failure.toString())
    }

    return when (outcome) {
        is InvolutionOutcome.Undone -> {
            val material = materialHalf.undoRestore(outcome.steps, job, asOf = record.executedAtMillis)
            val removed = structureHalf.restore(takeAway, StructurePass(force = true), CargoPolicy(ledgerCargoFor = undoLedgerCargoFor))

            // Return-to-vanished-drop from an entity hull
            val hullAt = HashMap<UUID, HolderId>()
            for (step in takeAway) {
                if (step is StructureStep.RemoveEntity) {
                    hullAt[step.entity] = HolderId.Block(step.at.world, step.at.x, step.at.y, step.at.z)
                }
            }
            val respawned = materialHalf.respawnReturnedDrops(outcome.steps, job, hullAt)
            services.jobs.markUndone(job)

            // Physics was off; wake redstone after both halves
            structureHalf.wakeRedstone(
                (restored.applied.asSequence() + removed.applied.asSequence()).map { it.at } +
                    undoDeltas.keys.asSequence().filterIsInstance<HolderId.Block>()
                        .map { BlockPos(it.world, it.x, it.y, it.z) },
            )
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
        is InvolutionOutcome.AlreadyUndone -> {
            structureHalf.restore(takeAway, StructurePass(force = true), CargoPolicy(ledgerCargoFor = undoLedgerCargoFor))
            services.jobs.markUndone(job)
            UndoResult.AlreadyUndone
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
