package com.tracel.plugin.rollback.survey.rooting

import com.tracel.model.holder.HolderId
import com.tracel.model.lot.LotId
import java.util.*

/**
 * Records where a lot's item traces back to before the window opened — the "home" that undo
 * will eventually put it back into.
 */
internal fun MutableMap<LotId, HolderId>.rootedAt(
    lot: LotId,
    source: HolderId,
    keepOn: Set<UUID> = emptySet(),
) {
    val current = this[lot]
    if (source is HolderId.Player && current is HolderId.Entity && current.uuid in keepOn) return
    if (source !is HolderId.Source) {
        put(lot, source)
        return
    }
    if (containsKey(lot)) return
    put(lot, source)
}

/** [rootedAt] for one flow of a transaction. */
internal fun MutableMap<LotId, HolderId>.rootedByFlow(
    lot: LotId,
    from: HolderId,
    passedThrough: Map<HolderId, HolderId>,
    keepOn: Set<UUID> = emptySet(),
) {
    val mint = passedThrough[from]
    if (mint == null) rootedAt(lot, from, keepOn) else put(lot, mint)
}
