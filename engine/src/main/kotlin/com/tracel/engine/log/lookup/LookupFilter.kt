package com.tracel.engine.log.lookup

import com.tracel.engine.log.TransactionLog
import com.tracel.engine.world.WorldLog
import com.tracel.model.cause.CauseKind
import com.tracel.model.holder.HolderId
import com.tracel.model.world.ActionKind
import com.tracel.model.world.WorldId

/**
 * One question for the [TransactionLog] and the [WorldLog] alike. Every field that is set narrows the answer; a filter
 * with nothing set lets everything through.
 */
public data class LookupFilter(
    public val holders: Set<HolderId> = emptySet(),
    public val excludedHolders: Set<HolderId> = emptySet(),
    public val material: String? = null,
    public val blockMaterials: Set<String> = emptySet(),
    public val causes: Set<CauseKind> = emptySet(),
    public val worldCauses: Set<CauseKind>? = null,
    public val excludedCauses: Set<CauseKind> = emptySet(),
    public val actions: Set<ActionKind> = emptySet(),
    public val since: Long? = null,
    public val until: Long? = null,
    public val region: LookupRegion? = null,
    public val world: WorldId? = null,
    public val limit: Int = 100,
    public val offset: Int = 0,
) {
    /**
     * Whether something that happened at [epochMillis] lies between [since] and [until], either end left open if unset.
     */
    public fun admitsTime(epochMillis: Long): Boolean =
        (since == null || epochMillis >= since) && (until == null || epochMillis <= until)

    /** Whether [cause] is one of [among], or [among] is empty, and is not one of [excludedCauses]. */
    public fun admitsCause(cause: CauseKind, among: Set<CauseKind> = causes): Boolean =
        (among.isEmpty() || cause in among) && (excludedCauses.isEmpty() || cause !in excludedCauses)
}
