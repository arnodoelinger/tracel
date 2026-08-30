package com.tracel.engine.rollback.plan

import com.tracel.model.holder.HolderId
import com.tracel.model.id.LotId
import com.tracel.model.id.Quantity
import com.tracel.model.id.RollbackJobId

/** Where a dry-run resolve of a lot lands: an addressable holder, a fan-out that needs resolving further, or nowhere. */
internal sealed interface ResolvedLocation {
    data class Holder(val lotId: LotId, val holder: HolderId, val quantity: Quantity) : ResolvedLocation
    data class Split(val children: List<LotId>) : ResolvedLocation
    data class Settled(val lotId: LotId, val byJob: RollbackJobId) : ResolvedLocation
}
