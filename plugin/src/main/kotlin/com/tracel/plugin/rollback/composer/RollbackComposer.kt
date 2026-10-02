package com.tracel.plugin.rollback.composer

import com.tracel.engine.log.LookupFilter
import com.tracel.model.holder.HolderId
import com.tracel.model.id.RollbackJobId
import com.tracel.plugin.TracelServices
import com.tracel.plugin.rollback.RollbackGenius
import com.tracel.plugin.rollback.material.MaterialHalf
import com.tracel.plugin.rollback.result.outcome.Planned
import com.tracel.plugin.rollback.result.outcome.RollbackResult
import com.tracel.plugin.rollback.result.outcome.UndoResult
import com.tracel.plugin.rollback.structure.StructureHalf
import com.tracel.plugin.rollback.survey.WorldCensus
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** How many of a player's latest rollbacks a button may still point at. */
private const val UNDO_LOOKBACK = 64

/**
 * Entry point: [plan] reads, [apply] writes, [undo] takes a job back. Nothing else starts a rollback.
 *
 * Apply: preflight -> freeze -> reserve -> ledger + create + prompt destroy in one wait -> items +
 * contested destroy -> save job -> wake redstone.
 *
 * Undo mirrors it: put back -> ledger -> items -> take away. Items move only after the ledger and
 * the created structure — a chest that shows up after its contents is empty.
 *
 * Better not to touch composer at all.
 */
class RollbackComposer(
    internal val services: TracelServices,
    internal val structureHalf: StructureHalf,
    internal val materialHalf: MaterialHalf,
    internal val worldCensus: WorldCensus,
) : RollbackGenius {
    private val inFlight = AtomicInteger()
    private val gate = AtomicBoolean()

    override val isRunning: Boolean get() = gate.get() || inFlight.get() > 0

    override fun claimGate(): Boolean = gate.compareAndSet(false, true)

    override fun releaseGate() {
        gate.set(false)
    }

    override suspend fun plan(
        filter: LookupFilter,
        structure: Boolean,
        material: Boolean,
    ): Planned = planRollback(filter, structure, material)

    override suspend fun apply(planned: Planned, strict: Boolean): RollbackResult = tracked {
        applyTracked(planned, strict)
    }

    override suspend fun undo(job: RollbackJobId): UndoResult = tracked { undoTracked(job) }

    override suspend fun lastUndoable(by: HolderId?): RollbackJobId? =
        services.jobs.undoableBy(by, limit = 1).firstOrNull()

    override suspend fun isUndoable(by: HolderId?, job: RollbackJobId): Boolean =
        job in services.jobs.undoableBy(by, limit = UNDO_LOOKBACK)

    internal inline fun <T> tracked(block: () -> T): T {
        inFlight.incrementAndGet()
        try {
            return block()
        } finally {
            inFlight.decrementAndGet()
        }
    }
}
