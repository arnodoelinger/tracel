package com.tracel.engine.log

import com.tracel.annotations.CauseKind
import com.tracel.model.holder.HolderId

/**
 * Lookup query over the [TransactionLog]: every non-empty field narrows the result,
 * an empty [LookupFilter] matches everything.
 */
public data class LookupFilter(
    public val holders: Set<HolderId> = emptySet(),
    public val excludedHolders: Set<HolderId> = emptySet(),
    public val material: String? = null,
    public val causes: Set<CauseKind> = emptySet(),
    public val since: Long? = null,
    public val until: Long? = null,
    public val limit: Int = 100,
    public val offset: Int = 0,
)
