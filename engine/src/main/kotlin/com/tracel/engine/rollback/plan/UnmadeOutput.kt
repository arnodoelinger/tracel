package com.tracel.engine.rollback.plan

import com.tracel.model.holder.HolderId
import com.tracel.model.id.LotId

public data class UnmadeOutput(
    /** Lot ID. */
    public val lotId: LotId,

    /** Holder ID. */
    public val holder: HolderId
)
