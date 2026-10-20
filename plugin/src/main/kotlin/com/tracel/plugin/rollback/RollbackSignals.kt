package com.tracel.plugin.rollback

import com.tracel.model.rollback.RollbackJobId
import com.tracel.plugin.rollback.result.outcome.Planned
import com.tracel.plugin.rollback.result.outcome.RollbackResult
import com.tracel.plugin.rollback.result.outcome.UndoResult

/** Who hears rollbacks start and end. Must not throw, and must not keep a rollback waiting. */
interface RollbackSignals {
    /** [planned] is about to be applied; the number returned comes back in [finished]. */
    fun started(planned: Planned): Long

    /** The run [started] numbered is over: [result] when it returned, `null` and [failure] when it threw. */
    fun finished(run: Long, planned: Planned, result: RollbackResult?, failure: Throwable?)

    /** An undo returned [result]. */
    fun undone(result: UndoResult)
}

/** [inner] with [signals] told about every apply and undo, whoever asked for it. */
class SignaledRollbacks(
    private val inner: RollbackGenius,
    private val signals: RollbackSignals,
) : RollbackGenius by inner {
    override suspend fun apply(planned: Planned, strict: Boolean): RollbackResult {
        val run = signals.started(planned)
        val result = try {
            inner.apply(planned, strict)
        } catch (failure: Throwable) {
            signals.finished(run, planned, null, failure)
            throw failure
        }
        signals.finished(run, planned, result, null)
        return result
    }

    override suspend fun undo(job: RollbackJobId): UndoResult = inner.undo(job).also(signals::undone)
}
