package com.tracel.engine.ledger.repository

import com.tracel.model.holder.HolderId

/** Lots of one pack, oldest first, all at [holder] and all untouched since: no edge leads out of any. */
public class PlacedRun(
    public val holder: HolderId,
    public val lots: LongArray,
    public val quantities: LongArray,
)
