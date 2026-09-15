package com.tracel.plugin.rollback

import com.tracel.engine.log.LookupFilter
import com.tracel.model.id.RollbackJobId
import com.tracel.plugin.rollback.result.outcome.Planned
import com.tracel.plugin.rollback.result.outcome.RollbackResult
import com.tracel.plugin.rollback.result.outcome.UndoResult
import com.tracel.plugin.rollback.trace.PhaseTimings
import com.tracel.plugin.rollback.trace.RollbackTrace

/**
 * What commands talk to.
 *
 * - [plan] reads
 * - [apply] writes
 * - [undo] takes a job back.
 */
interface RollbackGenius {
    /** In-flight gate: something is mid-apply or mid-undo. */
    val isRunning: Boolean

    /** Plan only. */
    suspend fun plan(
        filter: LookupFilter,
        structure: Boolean = true,
        material: Boolean = true,
        trace: RollbackTrace = PhaseTimings(), // TODO: remove me
    ): Planned

    /** Preflight then apply. [strict]: skip cells the world has moved on from. */
    suspend fun apply(planned: Planned, strict: Boolean = false): RollbackResult

    /** Mirror of apply: put-back structure, material, take-away last. */
    suspend fun undo(job: RollbackJobId): UndoResult

    /** The most recent job that has not been undone. */
    suspend fun lastUndoable(): RollbackJobId?
}
