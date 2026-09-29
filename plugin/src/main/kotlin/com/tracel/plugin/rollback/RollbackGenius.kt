package com.tracel.plugin.rollback

import com.tracel.engine.log.LookupFilter
import com.tracel.model.holder.HolderId
import com.tracel.model.id.RollbackJobId
import com.tracel.plugin.rollback.result.outcome.Planned
import com.tracel.plugin.rollback.result.outcome.RollbackResult
import com.tracel.plugin.rollback.result.outcome.UndoResult

/**
 * What commands talk to.
 *
 * - [plan] reads
 * - [apply] writes
 * - [undo] takes a job back.
 */
interface RollbackGenius {
    /**
     * Takes the one rollback-or-undo slot for everything from planning to the report.
     * Checking [isRunning] and then launching let two admins a second apart both through.
     *
     * @return `false` when another one holds it.
     */
    fun claimGate(): Boolean

    /** Hands the slot [claimGate] took back. */
    fun releaseGate()

    val isRunning: Boolean

    /** Plan only. */
    suspend fun plan(
        filter: LookupFilter,
        structure: Boolean = true,
        material: Boolean = true,
    ): Planned

    /** Preflight then apply. [strict]: skip cells the world has moved on from. */
    suspend fun apply(planned: Planned, strict: Boolean = false): RollbackResult

    /** Mirror of apply: put-back structure, material, take-away last. */
    suspend fun undo(job: RollbackJobId): UndoResult

    /** [by]'s most recent job that has not been undone. */
    suspend fun lastUndoable(by: HolderId?): RollbackJobId?
}
