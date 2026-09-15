package com.tracel.plugin.rollback.result.outcome

import com.tracel.model.id.RollbackJobId
import com.tracel.plugin.rollback.result.report.RestorationReport
import com.tracel.plugin.rollback.result.report.StructureReport

/**
 * Apply the outcome of rollback.
 *
 * Refusals shared with undo: [Unreachable], [Blocked].
 */
sealed interface RollbackResult {
    data class Done(
        val job: RollbackJobId,
        val plan: Planned,
        val structure: StructureReport,
        val material: RestorationReport,
    ) : RollbackResult

    data object Stale : RollbackResult
}
