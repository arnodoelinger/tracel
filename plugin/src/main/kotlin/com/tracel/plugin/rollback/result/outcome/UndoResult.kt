package com.tracel.plugin.rollback.result.outcome

import com.tracel.engine.rollback.involution.InvolutionStep
import com.tracel.model.id.RollbackJobId
import com.tracel.plugin.rollback.result.report.RestorationReport
import com.tracel.plugin.rollback.result.report.StructureReport

/** Undo outcome. Refusals shared with apply: [Unreachable], [Blocked]. */
sealed interface UndoResult {
    data class Done(
        val job: RollbackJobId,
        val structure: StructureReport,
        val material: RestorationReport,
        val steps: List<InvolutionStep>,
    ) : UndoResult

    data object NotFound : UndoResult
    data object AlreadyUndone : UndoResult

    data class OutOfOrder(val job: RollbackJobId, val newer: List<RollbackJobId>) : UndoResult
    data class Failed(val reason: String) : UndoResult
}
