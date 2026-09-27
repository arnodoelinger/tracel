package com.tracel.engine.rollback.plan

import com.tracel.model.holder.HolderId
import com.tracel.model.id.LotId
import com.tracel.model.id.Quantity
import com.tracel.model.id.RollbackJobId

/** Where a dry-run resolve of a lot lands. */
internal sealed interface ResolvedLocation {
    /** A lot currently held by a specific holder. */
    data class Holder(
        val lotId: LotId,
        val holder: HolderId,
        val quantity: Quantity,
    ) : ResolvedLocation

    /** A lot was split into child lots that must be resolved separately. */
    data class Split(
        val children: List<LotId>,
    ) : ResolvedLocation

    /** A lot has already been settled by another rollback job. */
    data class Settled(
        val lotId: LotId,
        val byJob: RollbackJobId,
    ) : ResolvedLocation

    /**
     * A lot that no longer has a resolvable location.
     *
     * This can happen when a rollback's [RollbackStep.Unmake] retired the lot without
     * creating a new link for it.
     *
     * Nothing can be safely restored from this lot.
     */
    data class Gone(
        val lotId: LotId,
    ) : ResolvedLocation

//     data class Destroyed(
//        val lotId: LotId,
//     ) : ResolvedLocation
}
