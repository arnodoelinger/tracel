package com.tracel.engine.ledger.craft

import com.tracel.engine.ledger.LotPortion
import com.tracel.model.lot.Lot

/**
 * What a craft did: the lot it produced, and what it ate to produce it.
 *
 * [consumed] holds one list per ingredient, in the order the ingredients were given.
 */
public data class CraftResult(public val output: Lot, public val consumed: List<List<LotPortion>>)
