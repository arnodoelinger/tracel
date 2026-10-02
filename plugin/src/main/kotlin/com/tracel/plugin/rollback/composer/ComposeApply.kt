package com.tracel.plugin.rollback.composer

import com.tracel.annotations.Unstable
import com.tracel.engine.rollback.job.Reservation
import com.tracel.engine.rollback.plan.RollbackStep
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.engine.rollback.structure.inverse
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.plugin.rollback.result.outcome.*
import com.tracel.plugin.rollback.result.report.SkippedStep
import com.tracel.plugin.rollback.result.report.StructureReport
import com.tracel.plugin.rollback.structure.StructurePass
import com.tracel.plugin.rollback.structure.redstone.redstoneCells
import kotlinx.coroutines.launch

@Unstable
private const val STALE_RETRIES = 3

/** Preflight both halves, then freeze and hand off. Nothing moves until both preflights pass. */
internal suspend fun RollbackComposer.applyTracked(planned: Planned, strict: Boolean): RollbackResult {
    val composite = planned.composite
    val allStructure = composite.create + composite.destroy

    when (val preflight = structureHalf.preflight(allStructure)) {
        is Unreachable -> return preflight
        PreflightResult.Ok -> Unit
    }

    var attempt = planned
    val left = ArrayList<SkippedStep>()
    repeat(STALE_RETRIES) {
        var deltas =
            materialHalf.deltasFor(attempt.composite.material, attempt.target)

        // A hull or container that will refuse to go must not have its item handed back either, or both exist
        val refused = services.leftHolding(attempt.composite.destroy, deltas)
        if (refused.isNotEmpty()) {
            left += attempt.composite.destroy.filter { it.placedHolder() in refused }
                .map { SkippedStep(it.at, "still holds material nobody withdrew") }
            attempt = attempt.without(refused)
            deltas = materialHalf.deltasFor(attempt.composite.material, attempt.target)
        }
        when (val preflight = materialHalf.preflight(deltas)) {
            is Unreachable -> return preflight
            PreflightResult.Ok -> Unit
        }

        val held = deltas.filter { (holder, moved) -> holder !is HolderId.Player || moved.values.any { it < 0L } }.keys
        var stale: Reservation.Stale? = null
        val result = services.frozen.whileFrozen(held) { applyReserved(attempt, strict, deltas) { stale = it } }
        val fresh = stale ?: return if (result is RollbackResult.Done && left.isNotEmpty()) {
            result.copy(structure = result.structure + StructureReport(emptyList(), left))
        } else {
            result
        }
        attempt = attempt.copy(composite = attempt.composite.copy(material = fresh.replan), witness = fresh.replannedAt)
    }
    return RollbackResult.Stale
}

/** Reserve, run both waits, then restore whatever structure moved if the ledger failed. */
private suspend fun RollbackComposer.applyReserved(
    planned: Planned,
    strict: Boolean,
    deltas: Map<HolderId, Map<ItemKey, Long>>,
    onStale: (Reservation.Stale) -> Unit,
): RollbackResult {
    val composite = planned.composite

    val job = services.counters.nextRollbackJobId()
    val startedAtMillis = System.currentTimeMillis()

    val layout = layoutOf(planned, deltas)
    for (step in composite.create) if (step is StructureStep.SpawnEntity) rememberHull(step)

    // Reserve before any block moves.
    // Mid-apply refusal left a half-restored world.
    val reservation = if (planned.roots.isEmpty()) null else services.rollback.reserve(
        job,
        planned.roots,
        planned.target,
        planned.vanished,
        prepared = composite.material,
        preparedAt = planned.witness,
        structural = planned.structural,
        covered = planned.covered,
    )
    when (reservation) {
        null, is Reservation.Granted -> Unit
        is Reservation.Blocked -> return Blocked(reservation.conflicts)
        is Reservation.Stale -> {
            onStale(reservation)
            return RollbackResult.Stale
        }
    }

    val first = try {
        ledgerAndStructure(planned, strict, deltas, layout, reservation)
    } catch (failure: Throwable) {
        if (reservation is Reservation.Granted) services.rollback.cancel(reservation)
        throw failure
    }

    // Ledger failed: inverse structure outside the canceled scope
    first.ledgerFailure?.let { failure ->
        val applied = first.created.applied + first.destroyedPrompt.applied
        if (applied.isNotEmpty()) {
            val inverted = structureHalf.restore(applied.map { it.inverse() }, StructurePass(force = true))
            structureHalf.settleFluids(inverted.applied, drain = false)
            val waking = inverted.applied.redstoneCells().toList()
            if (waking.isNotEmpty()) structureHalf.wakeRedstone(waking.asSequence())
        }
        throw failure
    }

    val later = materialAndContested(planned, strict, deltas, layout, job, startedAtMillis, first)
    val written = first.destroyedPrompt + later.extra
    val settled = structureHalf.settleFluids(first.created.applied + written.applied, drain = true)
    val destroyed = written + settled

    val waking = (first.created.applied + destroyed.applied).redstoneCells().toList()
    if (waking.isNotEmpty()) services.scope.launch { structureHalf.wakeRedstone(waking.asSequence()) }

    return RollbackResult.Done(job, planned, first.created + destroyed, later.material)
}

private fun StructureStep.placedHolder(): HolderId? = when (this) {
    is StructureStep.RemoveEntity -> HolderId.PlacedEntity(entity)
    is StructureStep.SetBlock -> HolderId.PlacedBlock(at.world, at.x, at.y, at.z)
    else -> null
}

private fun Planned.without(placed: Set<HolderId>): Planned {
    val steps = composite.material.steps.filterNot { step ->
        when (step) {
            is RollbackStep.Take -> step.holder in placed
            is RollbackStep.TakeRun -> step.holder in placed
            else -> false
        }
    }
    return copy(
        composite = composite.copy(
            destroy = composite.destroy.filterNot { it.placedHolder() in placed },
            material = composite.material.copy(steps = steps),
        ),
        covered = covered?.minus(placed),
    )
}
