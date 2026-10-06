package com.tracel.engine.ledger.repository

import com.tracel.model.id.LotId

/** What [LotRepository.placedRuns] sorted out: [runs] to take whole, [rest] to walk one by one. */
public class PlacedRuns(public val runs: List<PlacedRun>, public val rest: List<LotId>)
