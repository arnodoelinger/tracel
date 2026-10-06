package com.tracel.engine.rollback.plan.step

import com.tracel.model.holder.HolderId
import com.tracel.model.id.LotId

/** A piece of a craft's output that is destroyed by unmaking it: lot [lotId], at [holder]. */
public data class UnmadeOutput(
    /** Lot ID. */
    public val lotId: LotId,

    /** Holder ID. */
    public val holder: HolderId
)
