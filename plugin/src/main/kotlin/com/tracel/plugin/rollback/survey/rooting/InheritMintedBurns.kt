package com.tracel.plugin.rollback.survey.rooting

import com.tracel.model.holder.HolderId
import com.tracel.model.lot.LotId
import java.util.*

/**
 * For every lot still sitting in a "Sink" (something the item was consumed into, e.g. fire), walk
 * up its split ancestry via [parentOf] until an ancestor rooted at a "SOURCE" turns up, and inherit
 * that "SOURCE" instead of the chest.
 *
 * That way the burned half vanishes on undo like it should, while a genuinely real lot (never touched
 * by a source) keeps its real root untouched.
 */
internal fun MutableMap<LotId, HolderId>.inheritMintedBurns(
    sittingAt: Map<LotId, HolderId>,
    parentOf: Map<LotId, LotId>,
) {
    for (lot in keys.toList()) {
        if (sittingAt[lot] !is HolderId.Sink) continue
        var id = lot
        val seen = HashSet<LotId>()
        while (seen.add(id)) {
            val origin = this[id]
            if (origin is HolderId.Source) {
                this[lot] = origin
                break
            }
            id = parentOf[id] ?: break
        }
    }
}
