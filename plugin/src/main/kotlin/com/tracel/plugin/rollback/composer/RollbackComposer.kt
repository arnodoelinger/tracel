package com.tracel.plugin.rollback.composer

import com.tracel.engine.log.LookupFilter
import com.tracel.model.id.RollbackJobId
import com.tracel.plugin.TracelServices
import com.tracel.plugin.rollback.RollbackGenius
import com.tracel.plugin.rollback.material.MaterialHalf
import com.tracel.plugin.rollback.survey.WorldCensus
import com.tracel.plugin.rollback.result.outcome.Planned
import com.tracel.plugin.rollback.result.outcome.RollbackResult
import com.tracel.plugin.rollback.result.outcome.UndoResult
import com.tracel.plugin.rollback.structure.StructureHalf
import com.tracel.plugin.rollback.trace.RollbackTrace
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
        trace: RollbackTrace,
    ): Planned = planRollback(filter, structure, material, trace)

    override suspend fun apply(planned: Planned, strict: Boolean): RollbackResult = tracked {
        applyTracked(planned, strict)
    }

    override suspend fun undo(job: RollbackJobId): UndoResult = tracked { undoTracked(job) }

    override suspend fun lastUndoable(): RollbackJobId? = services.jobs.undoable(limit = 1).firstOrNull()

    internal inline fun <T> tracked(block: () -> T): T {
        inFlight.incrementAndGet()
        try {
            return block()
        } finally {
            inFlight.decrementAndGet()
        }
    }
}
