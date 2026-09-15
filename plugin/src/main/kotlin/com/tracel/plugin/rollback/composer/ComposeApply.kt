package com.tracel.plugin.rollback.composer

import com.tracel.engine.rollback.job.Reservation
import com.tracel.engine.rollback.structure.inverse
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.model.world.BlockPos
import com.tracel.plugin.rollback.result.outcome.Blocked
import com.tracel.plugin.rollback.result.outcome.Planned
import com.tracel.plugin.rollback.result.outcome.PreflightResult
import com.tracel.plugin.rollback.result.outcome.RollbackResult
import com.tracel.plugin.rollback.result.outcome.Unreachable
import com.tracel.plugin.rollback.structure.StructurePass

/** Preflight both halves, then freeze and hand off. Nothing moves until both preflights pass. */
internal suspend fun RollbackComposer.applyTracked(planned: Planned, strict: Boolean): RollbackResult {
    val composite = planned.composite
    val allStructure = composite.create + composite.destroy

    when (val preflight = planned.trace.span("preflight world") { structureHalf.preflight(allStructure) }) {
        is Unreachable -> return preflight
        PreflightResult.Ok -> Unit
    }

    // Before either half mutates
    val deltas = planned.trace.span("material deltas") { materialHalf.deltasFor(composite.material, planned.target) }
    when (val preflight = materialHalf.preflight(deltas)) {
        is Unreachable -> return preflight
        PreflightResult.Ok -> Unit
    }

    return services.frozen.whileFrozen(deltas.keys) { applyReserved(planned, strict, deltas) }
}

/** Reserve, run both waits, then restore whatever structure moved if the ledger failed. */
private suspend fun RollbackComposer.applyReserved(
    planned: Planned,
    strict: Boolean,
    deltas: Map<HolderId, Map<ItemKey, Long>>,
): RollbackResult {
    val composite = planned.composite

    val job = services.counters.nextRollbackJobId()
    val startedAtMillis = System.currentTimeMillis()

    val layout = layoutOf(planned, deltas)

    // Reserve before any block moves.
    // Mid-apply refusal left a half-restored world.
    val reservation = if (planned.roots.isEmpty()) null else planned.trace.span("reserve") {
        services.rollback.reserve(
            job, planned.roots, planned.target, planned.vanished,
            prepared = composite.material, preparedAt = planned.witness,
        )
    }
    when (reservation) {
        null, is Reservation.Granted -> Unit
        is Reservation.Blocked -> return Blocked(reservation.conflicts)
        is Reservation.Stale -> return RollbackResult.Stale
    }

    val first = ledgerAndStructure(planned, strict, deltas, layout, reservation)

    // Ledger failed: inverse structure outside the canceled scope
    first.ledgerFailure?.let { failure ->
        val applied = first.created.applied + first.destroyedPrompt.applied
        if (applied.isNotEmpty()) {
            structureHalf.restore(applied.map { it.inverse() }, StructurePass(force = true))
        }
        throw failure
    }

    val later = materialAndContested(planned, strict, deltas, layout, job, startedAtMillis, first)
    val destroyed = first.destroyedPrompt + later.extra

    planned.trace.span("wake redstone") {
        structureHalf.wakeRedstone(
            (first.created.applied.asSequence() + destroyed.applied.asSequence()).map { it.at } +
                deltas.keys.asSequence().filterIsInstance<HolderId.Block>().map { BlockPos(it.world, it.x, it.y, it.z) },
        )
    }

    return RollbackResult.Done(job, planned, first.created + destroyed, later.material)
}
