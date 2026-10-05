package com.tracel.storage.capture

import com.tracel.model.transaction.CauseKind
import com.tracel.model.world.ActionKind

/** One event's block edits still as interned ids: what the ring holds, with nothing resolved. */
class RawBlockEdits(
    val action: ActionKind,
    val cause: CauseKind,
    val causedById: Int,
    val epochMillis: Long,
    val worldId: Int,
    val coordinates: IntArray,
    val befores: IntArray,
    val afters: IntArray,
    val count: Int,
)
