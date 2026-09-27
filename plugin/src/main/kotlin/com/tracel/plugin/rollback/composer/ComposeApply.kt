package com.tracel.plugin.rollback.composer

import com.tracel.annotations.Unstable
import com.tracel.engine.rollback.job.Reservation
import com.tracel.engine.rollback.structure.inverse
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.plugin.rollback.result.outcome.*
import com.tracel.plugin.rollback.structure.StructurePass
import com.tracel.plugin.rollback.structure.redstone.redstoneCells
import kotlinx.coroutines.launch

@Unstable
private const val STALE_RETRIES = 3

/** Preflight both halves, then freeze and hand off. Nothing moves until both preflights pass. */
internal suspend fun RollbackComposer.applyTracked(planned: Planned, strict: Boolean): RollbackResult {
    val composite = planned.composite
    val allStructure = composite.create + composite.destroy

    when (val preflight = planned.trace.span("preflight world") { structureHalf.preflight(allStructure) }) {
        is Unreachable -> return preflight
        PreflightResult.Ok -> Unit
    }

    var attempt = planned
    repeat(STALE_RETRIES) {
        val deltas = attempt.trace.span("material deltas") { materialHalf.deltasFor(attempt.composite.material, attempt.target) }
        when (val preflight = materialHalf.preflight(deltas)) {
            is Unreachable -> return preflight
            PreflightResult.Ok -> Unit
        }

        val held = deltas.filter { (holder, moved) -> holder !is HolderId.Player || moved.values.any { it < 0L } }.keys
        var stale: Reservation.Stale? = null
        val result = services.frozen.whileFrozen(held) { applyReserved(attempt, strict, deltas) { stale = it } }
        val fresh = stale ?: return result
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

    // Reserve before any block moves.
    // Mid-apply refusal left a half-restored world.
    val reservation = if (planned.roots.isEmpty()) null else planned.trace.span("reserve") {
        services.rollback.reserve(
            job, planned.roots, planned.target, planned.vanished,
            prepared = composite.material, preparedAt = planned.witness, structural = planned.structural, covered = planned.covered,
        )
    }
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
    val settled = planned.trace.span("settle liquid") {
        structureHalf.settleFluids(first.created.applied + written.applied, drain = true)
    }
    val destroyed = written + settled

    planned.trace.span("wake redstone") {
        val waking = (first.created.applied + destroyed.applied).redstoneCells().toList()
        if (waking.isNotEmpty()) services.scope.launch { structureHalf.wakeRedstone(waking.asSequence()) }
    }

    return RollbackResult.Done(job, planned, first.created + destroyed, later.material)
}
