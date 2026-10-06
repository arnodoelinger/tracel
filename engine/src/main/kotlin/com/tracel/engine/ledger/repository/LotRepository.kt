package com.tracel.engine.ledger.repository

import com.tracel.model.id.LotId
import com.tracel.platform.storage.UnitOfWork

/** Storage port for lots, their edges, and where they currently sit. */
public interface LotRepository : UnitOfWork, Lots, LotEdges, AccountQueues, Placements {
    /** Moves on every committed change. Zero where nobody counts. */
    public suspend fun version(): Long = 0L

    /**
     * Whether any of [lots] changed after [witness], a [version] read earlier. Without stamps, whether anything did.
     */
    public suspend fun changedSince(lots: Collection<LotId>, witness: Long): Boolean = version() != witness
}
