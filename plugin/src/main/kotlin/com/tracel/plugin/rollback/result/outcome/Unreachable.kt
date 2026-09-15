package com.tracel.plugin.rollback.result.outcome

import com.tracel.model.holder.HolderId

/**
 * Nothing was changed: [holder] is out of reach.
 *
 * The same refusal from preflight, apply and undo.
 */
data class Unreachable(val holder: HolderId, val reason: String) : PreflightResult, RollbackResult, UndoResult
