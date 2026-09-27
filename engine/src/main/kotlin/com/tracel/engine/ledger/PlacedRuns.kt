package com.tracel.engine.ledger

import com.tracel.model.holder.HolderId
import com.tracel.model.id.LotId

/** Lots of one pack, oldest first, all at [holder] and all untouched since: no edge leads out of any. */
public class PlacedRun(
    public val holder: HolderId,
    public val lots: LongArray,
    public val quantities: LongArray,
)

/** What [LotRepository.placedRuns] sorted out: [runs] to take whole, [rest] to walk one by one. */
public class PlacedRuns(public val runs: List<PlacedRun>, public val rest: List<LotId>)
