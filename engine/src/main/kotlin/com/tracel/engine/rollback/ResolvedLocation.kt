package com.tracel.engine.rollback

import com.tracel.model.holder.HolderId
import com.tracel.model.id.LotId
import com.tracel.model.id.Quantity

/** Where a dry-run resolve of a lot of lands: an addressable holder, or a fan-out that needs resolving further. */
internal sealed interface ResolvedLocation {
    data class Holder(val lotId: LotId, val holder: HolderId, val quantity: Quantity) : ResolvedLocation
    data class Split(val children: List<LotId>) : ResolvedLocation
}
