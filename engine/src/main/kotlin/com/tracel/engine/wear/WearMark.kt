package com.tracel.engine.wear

import com.tracel.model.id.LotId

/**
 * One change to a tool's durability, from [before] to [after] at [epochMillis], told against the lot [lotId] holds it
 * as.
 */
public data class WearMark(
    public val lotId: LotId,
    public val epochMillis: Long,
    public val before: Int,
    public val after: Int,
)
