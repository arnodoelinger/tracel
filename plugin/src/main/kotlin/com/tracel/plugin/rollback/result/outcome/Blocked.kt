package com.tracel.plugin.rollback.result.outcome

import com.tracel.model.id.LotId
import com.tracel.model.id.RollbackJobId

/** Nothing was changed: other jobs still hold leases on these lots. */
data class Blocked(val conflicts: Map<LotId, RollbackJobId>) : RollbackResult, UndoResult
